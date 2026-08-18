package io.github.ncorror.nekoflash.usb.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.ncorror.nekoflash.usb.android.AndroidUsbDescriptorMapper
import io.github.ncorror.nekoflash.usb.diagnostics.UsbDiagnosticStore
import io.github.ncorror.nekoflash.usb.diagnostics.UsbSessionSnapshot
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Mode
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-scoped owner of Android USB discovery, permission and re-enumeration.
 *
 * Transport opening/claiming is intentionally not added in this stage. A permitted
 * candidate is captured as diagnostic evidence and becomes the current descriptor
 * generation until a real ADB/Fastboot transport is integrated.
 */
class UsbSessionCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = UsbDiagnosticStore(appContext)
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    private val permissionTimeouts = mutableMapOf<Int, Runnable>()
    private val sessionSequence = AtomicLong(0L)

    private var started = false
    private var startupScanGate = UsbSessionLifecyclePolicy.StartupScanGate()
    private var permissionRegistry = UsbPermissionPolicy.Registry()
    private var currentCandidate: Candidate? = null
    private var modeSwitchWatch: UsbSessionLifecyclePolicy.ModeSwitchWatch? = null

    private val startupRunnable = Runnable { runStartupScan() }
    private val modeSwitchRunnable = Runnable { runModeSwitchTick() }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started) return
            if (intent?.action == permissionAction) {
                handlePermissionResult(intent)
            }
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started) return
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = intent.usbDeviceExtra()
                if (device == null) {
                    diagnostics.event("WARN", "USB_DETACHED_MISSING_DEVICE")
                } else {
                    handleDetached(device)
                }
            }
        }
    }

    fun start() {
        if (started) return
        registerPermissionReceiver()
        registerDetachReceiver()
        started = true
        diagnostics.event("INFO", "USB_COORDINATOR_STARTED")
    }

    /**
     * Ends the current authorized USB automation lease. No transport exists yet,
     * so descriptor/session state can be discarded instead of surviving a UI exit.
     */
    fun stop() {
        if (!started) return
        started = false
        cancelStartupScan()
        stopModeSwitchWatch()
        permissionTimeouts.values.forEach(handler::removeCallbacks)
        permissionTimeouts.clear()
        permissionRegistry = UsbPermissionPolicy.Registry()
        currentCandidate = null
        appContext.unregisterReceiver(permissionReceiver)
        appContext.unregisterReceiver(detachReceiver)
        diagnostics.event("INFO", "USB_COORDINATOR_STOPPED")
    }

    private fun registerPermissionReceiver() {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                permissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerLegacyPermissionReceiver(filter)
        }
    }

    /**
     * API 26-32 keeps the pinned dynamic-receiver behavior. The permission
     * PendingIntent is package-scoped; changing this legacy delivery mechanism
     * further requires its own hardware-validated platform migration.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    private fun registerLegacyPermissionReceiver(filter: IntentFilter) {
        appContext.registerReceiver(permissionReceiver, filter)
    }

    /**
     * USB_DEVICE_DETACHED is a protected system broadcast, so this system-only
     * receiver intentionally uses the platform registration path without an
     * exported/not-exported flag.
     */
    private fun registerDetachReceiver() {
        appContext.registerReceiver(
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
        )
    }

    /**
     * Authorizes a UI entry that was visible before USB automation was activated.
     * A pre-gate attach payload is consumed without inspection, then the normal
     * startup scan observes the descriptors that are actually present now.
     */
    fun onEntryAuthorized(intent: Intent?): Long? {
        if (!started) return null
        if (
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            !intent.getBooleanExtra(EXTRA_USB_INTENT_CONSUMED, false)
        ) {
            intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)
            diagnostics.event("INFO", "USB_PREAUTH_ATTACH_NORMALIZED")
        }
        return onActivityCreated(intent)
    }

    /**
     * A new Activity instance starts a new legacy-equivalent startup-scan entry.
     * The returned generation is only a lifecycle token; USB state remains here.
     */
    fun onActivityCreated(intent: Intent?): Long? {
        if (!started) return null
        cancelStartupScan()
        startupScanGate = startupScanGate.nextUiEntry()
        diagnostics.event(
            "INFO",
            "USB_UI_ENTRY_STARTED",
            "generation=${startupScanGate.uiEntryGeneration}",
        )
        val attachHandled = handleAttachIntent(intent)
        if (!attachHandled) scheduleStartupScan()
        return startupScanGate.uiEntryGeneration
    }

    /** A new Intent for the same Activity does not re-arm or start a startup scan. */
    fun onActivityNewIntent(intent: Intent?) {
        if (!started) return
        handleAttachIntent(intent)
    }

    /** Cancels only the startup callback that belongs to the destroyed UI entry. */
    fun onActivityDestroyed(uiEntryGeneration: Long) {
        if (!started) return
        if (startupScanGate.uiEntryGeneration != uiEntryGeneration) return
        cancelStartupScan()
        diagnostics.event(
            "INFO",
            "USB_UI_ENTRY_ENDED",
            "generation=$uiEntryGeneration",
        )
    }

    /** Mirrors legacy handleAutoUsbIntent: true means this attach payload was consumed. */
    private fun handleAttachIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        if (intent.getBooleanExtra(EXTRA_USB_INTENT_CONSUMED, false)) return false

        val device = intent.usbDeviceExtra()
        intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)
        if (device == null) {
            diagnostics.event("WARN", "USB_ATTACHED_MISSING_DEVICE")
            return true
        }
        handleAttached(device)
        return true
    }

    fun diagnosticsDirectoryPath(): String = diagnostics.directoryPath()

    private fun scheduleStartupScan() {
        val decision = UsbSessionLifecyclePolicy.scheduleStartupScan(startupScanGate)
        startupScanGate = decision.gate
        val delayMs = decision.delayMs ?: return
        diagnostics.event("INFO", "USB_STARTUP_SCAN_SCHEDULED", "delayMs=$delayMs")
        handler.postDelayed(startupRunnable, delayMs)
    }

    private fun cancelStartupScan() {
        handler.removeCallbacks(startupRunnable)
    }

    private fun runStartupScan() {
        if (!started) return
        val descriptors = currentUsbDevices().map(AndroidUsbDescriptorMapper::map)
        val candidate = UsbSessionLifecyclePolicy.selectStartupCandidate(currentPhase(), descriptors)
        if (candidate == null) {
            diagnostics.event("INFO", "USB_STARTUP_SCAN_NO_UNIQUE_CANDIDATE", "devices=${descriptors.size}")
            return
        }
        diagnostics.event("INFO", "USB_STARTUP_CANDIDATE", candidate.summary())
        if (modeSwitchWatch != null) {
            diagnostics.event(
                "INFO",
                "USB_STARTUP_CANDIDATE_SUPERSEDES_MODE_SWITCH_WATCH",
                "generation=${startupScanGate.uiEntryGeneration}",
            )
            stopModeSwitchWatch()
        }
        requestAccess(candidate, automatic = true)
    }

    private fun handleAttached(device: UsbDevice) {
        val descriptor = AndroidUsbDescriptorMapper.map(device)
        val decision = UsbSessionLifecyclePolicy.onAttached(descriptor)
        if (decision.cancelStartupScan) cancelStartupScan()
        if (decision.stopModeSwitchWatch) stopModeSwitchWatch()

        diagnostics.event(
            "INFO",
            "USB_ATTACHED",
            "deviceId=${descriptor.deviceId} deviceName=${descriptor.deviceName} " +
                "vid=${descriptor.vendorId} pid=${descriptor.productId}",
        )
        val candidate = decision.candidate
        if (candidate == null) {
            diagnostics.event("WARN", "USB_ATTACHED_NO_CANDIDATE")
            return
        }
        requestAccess(candidate, automatic = true)
    }

    private fun requestAccess(candidate: Candidate, automatic: Boolean) {
        if (!started) return
        val device = findCurrentDevice(candidate.device)
        if (device == null) {
            diagnostics.event("WARN", "USB_CANDIDATE_DISAPPEARED", candidate.summary())
            return
        }
        val begin = UsbPermissionPolicy.beginAccess(
            registry = permissionRegistry,
            candidate = candidate,
            automatic = automatic,
            permissionAlreadyGranted = usbManager.hasPermission(device),
        )
        permissionRegistry = begin.registry

        when (val action = begin.action) {
            is UsbPermissionPolicy.Action.Connect -> acceptPermittedCandidate(action.candidate)
            is UsbPermissionPolicy.Action.RequestPermission -> {
                diagnostics.event("INFO", "USB_PERMISSION_REQUESTED", action.candidate.summary())
                schedulePermissionTimeout(device, action.timeoutMs)
                val permissionIntent = Intent(permissionAction).setPackage(appContext.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    device.deviceId,
                    permissionIntent,
                    flags,
                )
                usbManager.requestPermission(device, pendingIntent)
            }
            UsbPermissionPolicy.Action.PermissionDenied,
            UsbPermissionPolicy.Action.MissingDevice,
            UsbPermissionPolicy.Action.NoCandidate,
            -> error("beginAccess returned an impossible action")
        }
    }

    private fun handlePermissionResult(intent: Intent) {
        val device = intent.usbDeviceExtra()
        val descriptor = device?.let(AndroidUsbDescriptorMapper::map)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = permissionRegistry,
            device = descriptor,
            granted = granted,
        )
        permissionRegistry = result.registry
        result.timeoutDeviceIdToCancel?.let(::cancelPermissionTimeout)

        when (val action = result.action) {
            is UsbPermissionPolicy.Action.Connect -> {
                diagnostics.event("INFO", "USB_PERMISSION_GRANTED", action.candidate.summary())
                acceptPermittedCandidate(action.candidate)
            }
            UsbPermissionPolicy.Action.PermissionDenied ->
                diagnostics.event("WARN", "USB_PERMISSION_DENIED")
            UsbPermissionPolicy.Action.MissingDevice ->
                diagnostics.event("ERROR", "USB_PERMISSION_RESULT_MISSING_DEVICE")
            UsbPermissionPolicy.Action.NoCandidate ->
                diagnostics.event("WARN", "USB_PERMISSION_RESULT_NO_CANDIDATE")
            is UsbPermissionPolicy.Action.RequestPermission ->
                error("resolvePermissionResult returned an impossible action")
        }
    }

    private fun schedulePermissionTimeout(device: UsbDevice, delayMs: Long) {
        cancelPermissionTimeout(device.deviceId)
        val timeout = Runnable {
            permissionTimeouts.remove(device.deviceId)
            val permissionGrantedNow = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)
            val result = UsbPermissionPolicy.onTimeout(
                registry = permissionRegistry,
                requestedDeviceId = device.deviceId,
                permissionGrantedNow = permissionGrantedNow,
            )
            permissionRegistry = result.registry
            if (result.shouldReportNoResponse) {
                diagnostics.event("ERROR", "USB_PERMISSION_TIMEOUT", "deviceId=${device.deviceId}")
            } else {
                diagnostics.event("INFO", "USB_PERMISSION_TIMEOUT_SUPPRESSED", "deviceId=${device.deviceId}")
            }
        }
        permissionTimeouts[device.deviceId] = timeout
        handler.postDelayed(timeout, delayMs)
    }

    private fun cancelPermissionTimeout(deviceId: Int) {
        val timeout = permissionTimeouts.remove(deviceId) ?: return
        handler.removeCallbacks(timeout)
    }

    private fun acceptPermittedCandidate(candidate: Candidate) {
        if (currentCandidate?.stableKey == candidate.stableKey) {
            diagnostics.event("INFO", "USB_DUPLICATE_CANDIDATE_IGNORED", candidate.summary())
            return
        }
        currentCandidate = candidate
        val capturedAt = System.currentTimeMillis()
        val sessionId = "$capturedAt-${sessionSequence.incrementAndGet()}"
        val snapshot = UsbSessionSnapshot.capture(
            sessionId = sessionId,
            capturedAtEpochMs = capturedAt,
            candidate = candidate,
            host = hostSnapshot(),
        )
        diagnostics.writeSnapshot(snapshot)
        diagnostics.event(
            "INFO",
            "USB_CANDIDATE_READY",
            "sessionId=$sessionId ${candidate.summary()} transport=not-opened",
        )
    }

    private fun handleDetached(device: UsbDevice) {
        val descriptor = AndroidUsbDescriptorMapper.map(device)
        permissionRegistry = UsbPermissionPolicy.Registry(
            permissionRegistry.requests.filterNot { it.requestedDeviceId == descriptor.deviceId },
        )
        val decision = UsbSessionLifecyclePolicy.onDetached(currentCandidate, descriptor)
        diagnostics.event(
            "INFO",
            "USB_DETACHED",
            "deviceId=${descriptor.deviceId} deviceName=${descriptor.deviceName} current=${decision.disconnectCurrent}",
        )
        if (!decision.disconnectCurrent) return

        currentCandidate = null
        val watch = decision.modeSwitchWatch ?: return
        startModeSwitchWatch(watch)
    }

    private fun startModeSwitchWatch(watch: UsbSessionLifecyclePolicy.ModeSwitchWatch) {
        stopModeSwitchWatch()
        modeSwitchWatch = watch
        diagnostics.event(
            "INFO",
            "USB_MODE_SWITCH_WATCH_STARTED",
            "attempts=${watch.attemptsRemaining} intervalMs=${UsbSessionLifecyclePolicy.MODE_SWITCH_SCAN_INTERVAL_MS}",
        )
        handler.postDelayed(modeSwitchRunnable, UsbSessionLifecyclePolicy.MODE_SWITCH_SCAN_INTERVAL_MS)
    }

    private fun stopModeSwitchWatch() {
        handler.removeCallbacks(modeSwitchRunnable)
        modeSwitchWatch = null
    }

    private fun runModeSwitchTick() {
        if (!started) return
        val watch = modeSwitchWatch ?: return
        val result = UsbSessionLifecyclePolicy.tickModeSwitch(
            watch = watch,
            devices = currentUsbDevices().map(AndroidUsbDescriptorMapper::map),
        )
        modeSwitchWatch = result.nextWatch
        val candidate = result.candidate
        if (candidate != null) {
            diagnostics.event("INFO", "USB_MODE_SWITCH_CANDIDATE", candidate.summary())
            requestAccess(candidate, automatic = true)
            return
        }
        val delayMs = result.scheduleNextAfterMs
        if (delayMs != null && result.nextWatch != null) {
            handler.postDelayed(modeSwitchRunnable, delayMs)
        } else {
            diagnostics.event("INFO", "USB_MODE_SWITCH_WATCH_FINISHED")
        }
    }

    private fun currentPhase(): UsbSessionLifecyclePolicy.ConnectionPhase = when (currentCandidate?.mode) {
        Mode.ADB -> UsbSessionLifecyclePolicy.ConnectionPhase.ADB
        Mode.FASTBOOT -> UsbSessionLifecyclePolicy.ConnectionPhase.FASTBOOT
        null -> UsbSessionLifecyclePolicy.ConnectionPhase.NONE
    }

    private fun currentUsbDevices(): Collection<UsbDevice> = usbManager.deviceList.values

    private fun findCurrentDevice(descriptor: UsbDeviceDescriptor): UsbDevice? =
        currentUsbDevices().firstOrNull { it.deviceId == descriptor.deviceId }

    private fun hostSnapshot() = UsbSessionSnapshot.HostSnapshot(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
    )

    private fun Candidate.summary(): String =
        "deviceId=${device.deviceId} vid=${device.vendorId} pid=${device.productId} " +
            "mode=${mode.name} match=${matchKind.name} interface=$interfaceIndex " +
            "in=$endpointInAddress out=$endpointOutAddress"

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val EXTRA_USB_INTENT_CONSUMED = "nekoflash_a2_usb_intent_consumed"
    }
}
