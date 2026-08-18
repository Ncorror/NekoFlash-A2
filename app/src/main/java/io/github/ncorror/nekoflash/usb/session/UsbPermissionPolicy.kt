package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor

/**
 * Pure USB permission bookkeeping extracted from the proven legacy flow.
 *
 * Android PendingIntent, BroadcastReceiver, Handler and UsbManager calls stay in
 * the application-scoped USB owner. This type only defines which request is
 * pending and what the next observable action must be.
 */
object UsbPermissionPolicy {
    const val RESPONSE_TIMEOUT_MS = 30_000L

    data class PendingRequest(
        val candidate: Candidate,
        val automatic: Boolean,
    ) {
        val requestedDeviceId: Int get() = candidate.device.deviceId
        val requestedDeviceName: String get() = candidate.device.deviceName
    }

    /**
     * Order is intentional. Legacy used mutableMapOf(), whose insertion order is
     * observable when a permission callback must fall back from deviceId to the
     * first pending request with the same deviceName.
     */
    data class Registry(
        val requests: List<PendingRequest> = emptyList(),
    ) {
        init {
            require(requests.map { it.requestedDeviceId }.distinct().size == requests.size) {
                "USB permission registry must contain at most one request per deviceId"
            }
        }

        val size: Int get() = requests.size

        fun requestForDeviceId(deviceId: Int): PendingRequest? =
            requests.firstOrNull { it.requestedDeviceId == deviceId }
    }

    sealed interface Action {
        data class Connect(
            val candidate: Candidate,
            val automatic: Boolean,
        ) : Action

        data class RequestPermission(
            val candidate: Candidate,
            val automatic: Boolean,
            val timeoutMs: Long,
        ) : Action

        data object PermissionDenied : Action
        data object MissingDevice : Action
        data object NoCandidate : Action
    }

    data class BeginResult(
        val registry: Registry,
        val action: Action,
    )

    data class PermissionResult(
        val registry: Registry,
        val action: Action,
        /**
         * Mirrors legacy timeout cancellation: cancel the timer keyed by the
         * deviceId carried by the callback, even if pending lookup later falls
         * back to deviceName and resolves a request created under another id.
         */
        val timeoutDeviceIdToCancel: Int?,
    )

    data class TimeoutResult(
        val registry: Registry,
        val shouldReportNoResponse: Boolean,
    )

    fun beginAccess(
        registry: Registry,
        candidate: Candidate,
        automatic: Boolean,
        permissionAlreadyGranted: Boolean,
    ): BeginResult {
        val request = PendingRequest(candidate, automatic)
        val tracked = put(registry, request)
        return if (permissionAlreadyGranted) {
            BeginResult(
                registry = removeByDeviceId(tracked, candidate.device.deviceId),
                action = Action.Connect(candidate, automatic),
            )
        } else {
            BeginResult(
                registry = tracked,
                action = Action.RequestPermission(
                    candidate = candidate,
                    automatic = automatic,
                    timeoutMs = RESPONSE_TIMEOUT_MS,
                ),
            )
        }
    }

    fun resolvePermissionResult(
        registry: Registry,
        device: UsbDeviceDescriptor?,
        granted: Boolean,
    ): PermissionResult {
        val timeoutDeviceIdToCancel = device?.deviceId

        if (!granted) {
            val nextRegistry = if (device == null) {
                registry
            } else {
                take(registry, device).registry
            }
            return PermissionResult(
                registry = nextRegistry,
                action = Action.PermissionDenied,
                timeoutDeviceIdToCancel = timeoutDeviceIdToCancel,
            )
        }

        if (device == null) {
            return PermissionResult(
                registry = registry,
                action = Action.MissingDevice,
                timeoutDeviceIdToCancel = null,
            )
        }

        val taken = take(registry, device)
        val pending = taken.request
        val rebound = pending?.candidate?.let { previous ->
            UsbInterfaceSelector.rebindCandidate(device, previous)
        }
        val candidate = rebound
            ?: UsbInterfaceSelector.selectPrimaryCandidate(device, allowGenericFastboot = true)

        return PermissionResult(
            registry = taken.registry,
            action = if (candidate == null) {
                Action.NoCandidate
            } else {
                Action.Connect(
                    candidate = candidate,
                    automatic = pending?.automatic ?: true,
                )
            },
            timeoutDeviceIdToCancel = timeoutDeviceIdToCancel,
        )
    }

    /**
     * Timeout removes only the request keyed by the timed-out deviceId. If
     * permission became granted without a callback, legacy suppressed the error
     * but did not connect implicitly; that behavior remains unchanged here.
     */
    fun onTimeout(
        registry: Registry,
        requestedDeviceId: Int,
        permissionGrantedNow: Boolean,
    ): TimeoutResult = TimeoutResult(
        registry = removeByDeviceId(registry, requestedDeviceId),
        shouldReportNoResponse = !permissionGrantedNow,
    )

    private data class TakeResult(
        val registry: Registry,
        val request: PendingRequest?,
    )

    private fun put(registry: Registry, request: PendingRequest): Registry {
        val index = registry.requests.indexOfFirst {
            it.requestedDeviceId == request.requestedDeviceId
        }
        if (index < 0) return Registry(registry.requests + request)

        val updated = registry.requests.toMutableList()
        updated[index] = request
        return Registry(updated)
    }

    private fun removeByDeviceId(registry: Registry, deviceId: Int): Registry {
        val index = registry.requests.indexOfFirst { it.requestedDeviceId == deviceId }
        if (index < 0) return registry
        return Registry(registry.requests.filterIndexed { requestIndex, _ -> requestIndex != index })
    }

    private fun take(registry: Registry, device: UsbDeviceDescriptor): TakeResult {
        val exactIndex = registry.requests.indexOfFirst {
            it.requestedDeviceId == device.deviceId
        }
        val index = if (exactIndex >= 0) {
            exactIndex
        } else {
            registry.requests.indexOfFirst {
                it.requestedDeviceName == device.deviceName
            }
        }
        if (index < 0) return TakeResult(registry, null)

        val request = registry.requests[index]
        val remaining = registry.requests.filterIndexed { requestIndex, _ -> requestIndex != index }
        return TakeResult(Registry(remaining), request)
    }
}
