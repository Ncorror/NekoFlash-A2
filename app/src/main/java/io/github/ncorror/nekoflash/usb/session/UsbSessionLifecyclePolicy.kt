package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor

/**
 * Pure attach, detach, startup-scan and mode-switch decisions.
 *
 * The application-scoped coordinator will own Android callbacks and scheduling;
 * this policy freezes the legacy selection and retry semantics before that move.
 */
object UsbSessionLifecyclePolicy {
    const val STARTUP_SCAN_DELAY_MS = 350L
    const val MODE_SWITCH_SCAN_INTERVAL_MS = 750L
    const val MODE_SWITCH_SCAN_ATTEMPTS = 16

    enum class ConnectionPhase {
        NONE,
        CONNECTING,
        FASTBOOT,
        ADB,
        ERROR,
    }

    data class StartupScheduleDecision(
        val startupScanMarkedScheduled: Boolean,
        val delayMs: Long?,
    )

    data class AttachDecision(
        val cancelStartupScan: Boolean,
        val stopModeSwitchWatch: Boolean,
        val candidate: Candidate?,
    )

    data class ModeSwitchWatch(
        val previousLogicalSignature: String?,
        val previousVendorId: Int?,
        val attemptsRemaining: Int = MODE_SWITCH_SCAN_ATTEMPTS,
    ) {
        val active: Boolean get() = attemptsRemaining > 0
    }

    data class DetachDecision(
        val pendingDeviceIdToRemove: Int,
        val disconnectCurrent: Boolean,
        val modeSwitchWatch: ModeSwitchWatch?,
    )

    data class ModeSwitchTickResult(
        val candidate: Candidate?,
        val nextWatch: ModeSwitchWatch?,
        val scheduleNextAfterMs: Long?,
    )

    /**
     * Startup enumeration preserves the legacy one-shot gate. Once marked
     * scheduled, another request is ignored until the caller resets the gate.
     */
    fun scheduleStartupScan(alreadyScheduled: Boolean): StartupScheduleDecision =
        if (alreadyScheduled) {
            StartupScheduleDecision(
                startupScanMarkedScheduled = true,
                delayMs = null,
            )
        } else {
            StartupScheduleDecision(
                startupScanMarkedScheduled = true,
                delayMs = STARTUP_SCAN_DELAY_MS,
            )
        }

    /**
     * Startup auto-connect is intentionally conservative: only NONE/ERROR state
     * and exactly one discovered candidate may advance automatically.
     */
    fun selectStartupCandidate(
        phase: ConnectionPhase,
        devices: Collection<UsbDeviceDescriptor>,
    ): Candidate? {
        if (phase != ConnectionPhase.NONE && phase != ConnectionPhase.ERROR) return null
        return UsbInterfaceSelector.findAutoConnectCandidates(devices).singleOrNull()
    }

    /**
     * A non-null attach cancels any pending startup scan and mode-switch watch
     * before descriptor classification, even when the device is unrecognized.
     * A system attach callback without a device leaves those schedulers alone.
     */
    fun onAttached(device: UsbDeviceDescriptor?): AttachDecision {
        if (device == null) {
            return AttachDecision(
                cancelStartupScan = false,
                stopModeSwitchWatch = false,
                candidate = null,
            )
        }
        return AttachDecision(
            cancelStartupScan = true,
            stopModeSwitchWatch = true,
            candidate = UsbInterfaceSelector.selectPrimaryCandidate(
                device,
                allowGenericFastboot = true,
            ),
        )
    }

    /** Mirrors DeviceViewModel.isCurrentUsbDevice from the pinned legacy baseline. */
    fun isCurrentDevice(
        current: UsbDeviceDescriptor,
        observed: UsbDeviceDescriptor,
    ): Boolean =
        current.deviceName == observed.deviceName ||
            (current.deviceId == observed.deviceId &&
                current.vendorId == observed.vendorId &&
                current.productId == observed.productId)

    /**
     * Every detach removes pending permission state for the exact detached id.
     * Only a detach matching the current candidate disconnects the transport and
     * starts re-enumeration tracking.
     */
    fun onDetached(
        current: Candidate?,
        detached: UsbDeviceDescriptor,
    ): DetachDecision {
        val isCurrent = current?.let { isCurrentDevice(it.device, detached) } == true
        return DetachDecision(
            pendingDeviceIdToRemove = detached.deviceId,
            disconnectCurrent = isCurrent,
            modeSwitchWatch = if (isCurrent) {
                ModeSwitchWatch(
                    previousLogicalSignature = current?.logicalSignature,
                    previousVendorId = current?.device?.vendorId ?: detached.vendorId,
                )
            } else {
                null
            },
        )
    }

    /**
     * Each tick performs one legacy selector pass. A unique changed candidate
     * ends the watch. A miss consumes one of sixteen attempts and schedules the
     * next pass 750 ms later while attempts remain.
     */
    fun tickModeSwitch(
        watch: ModeSwitchWatch,
        devices: Collection<UsbDeviceDescriptor>,
    ): ModeSwitchTickResult {
        if (!watch.active) {
            return ModeSwitchTickResult(
                candidate = null,
                nextWatch = null,
                scheduleNextAfterMs = null,
            )
        }

        val candidate = UsbInterfaceSelector.selectModeSwitchCandidate(
            devices = devices,
            previousLogicalSignature = watch.previousLogicalSignature,
            previousVendorId = watch.previousVendorId,
        )
        if (candidate != null) {
            return ModeSwitchTickResult(
                candidate = candidate,
                nextWatch = null,
                scheduleNextAfterMs = null,
            )
        }

        val remaining = watch.attemptsRemaining - 1
        return if (remaining > 0) {
            ModeSwitchTickResult(
                candidate = null,
                nextWatch = watch.copy(attemptsRemaining = remaining),
                scheduleNextAfterMs = MODE_SWITCH_SCAN_INTERVAL_MS,
            )
        } else {
            ModeSwitchTickResult(
                candidate = null,
                nextWatch = null,
                scheduleNextAfterMs = null,
            )
        }
    }
}
