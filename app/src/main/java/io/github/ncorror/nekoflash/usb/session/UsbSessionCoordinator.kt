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
import io.github.ncorror.nekoflash.adb.transport.AdbUsbTransport
import io.github.ncorror.nekoflash.fastboot.transport.FastbootUsbTransport
import io.github.ncorror.nekoflash.usb.android.AndroidUsbDescriptorMapper
import io.github.ncorror.nekoflash.usb.diagnostics.UsbDiagnosticStore
import io.github.ncorror.nekoflash.usb.diagnostics.UsbDiagnosticsZipExporter
import io.github.ncorror.nekoflash.usb.diagnostics.UsbSessionSnapshot
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Mode
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-scoped owner of Android USB discovery, permission and re-enumeration.
 *
 * Stage 6B opens ADB only after descriptor selection and Android permission. The first
 * post-6B Fastboot slice opens Fastboot only for fixed read-only `getvar:product`
 * qualification. Transport retries remain manual-only after failure.
 */
class UsbSessionCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = UsbDiagnosticStore(appContext)
    private val permissionCallbackIdentity = UsbPermissionCallbackIdentity(
        actionPrefix = "${appContext.packageName}.USB_PERMISSION",
        processToken = UUID.randomUUID().toString(),
    )
    private val permissionTimeouts = mutableMapOf<Int, Runnable>()
    private val sessionSequence = AtomicLong(0L)
    private val adbTransportGeneration = AtomicLong(0L)
    private val adbTransportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NekoFlash-ADB-Connect").apply { isDaemon = true }
    }
    private val adbTransportLock = Any()
    private val fastbootTransportGeneration = AtomicLong(0L)
    private val fastbootTransportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NekoFlash-Fastboot-Connect").apply { isDaemon = true }
    }
    private val fastbootTransportLock = Any()

    private var started = false
    private var activePermissionCallback: UsbPermissionCallbackIdentity.Callback? = null
    private var permissionReceiverRegistered = false
    private var detachReceiverRegistered = false
    private var startupScanGate = UsbSessionLifecyclePolicy.StartupScanGate()
    private var permissionRegistry = UsbPermissionPolicy.Registry()
    private var currentCandidate: Candidate? = null
    private var modeSwitchWatch: UsbSessionLifecyclePolicy.ModeSwitchWatch? = null
    private val observationStore = UsbSessionObservationStore()
    private var pendingManualConfirmation: PendingManualConfirmation? = null
    @Volatile private var activeAdbTransport: AdbUsbTransport? = null
    @Volatile private var activeFastbootTransport: FastbootUsbTransport? = null
    private var failedAdbStableKey: String? = null
    private var failedFastbootStableKey: String? = null

    private val startupRunnable = Runnable { runStartupScan() }
    private val modeSwitchRunnable = Runnable { runModeSwitchTick() }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started) return
            val expectedAction = activePermissionCallback?.action ?: return
            if (intent?.action == expectedAction) {
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

        val callback = permissionCallbackIdentity.nextCallback()
        activePermissionCallback = callback
        try {
            registerPermissionReceiver(callback.action)
            permissionReceiverRegistered = true
            registerDetachReceiver()
            detachReceiverRegistered = true
            started = true
            diagnostics.event(
                "INFO",
                "USB_COORDINATOR_STARTED",
                "permissionLease=${callback.generation}",
            )
        } catch (error: RuntimeException) {
            started = false
            unregisterReceiversSafely()
            activePermissionCallback = null
            diagnostics.event(
                "ERROR",
                "USB_COORDINATOR_START_FAILED",
                "permissionLease=${callback.generation} error=${error.javaClass.simpleName}",
            )
            throw error
        }
    }

    /** Ends the current authorized USB automation lease and its owned ADB transport. */
    fun stop() {
        if (!started) return
        started = false
        cancelStartupScan()
        stopModeSwitchWatch()
        permissionTimeouts.values.forEach(handler::removeCallbacks)
        permissionTimeouts.clear()
        permissionRegistry = UsbPermissionPolicy.Registry()
        stopAdbTransport("coordinator_stop")
        stopFastbootTransport("coordinator_stop")
        currentCandidate = null
        pendingManualConfirmation = null
        observationStore.publish(UsbSessionObservation())
        val endedPermissionLease = activePermissionCallback?.generation
        activePermissionCallback = null
        unregisterReceiversSafely()
        diagnostics.event(
            "INFO",
            "USB_COORDINATOR_STOPPED",
            "permissionLease=${endedPermissionLease ?: "none"}",
        )
    }


    /**
     * Replaces the UI observer without transferring USB ownership out of this
     * Application-scoped coordinator. The new listener receives the current
     * immutable snapshot immediately.
     */
    fun replaceObservationListener(listener: (UsbSessionObservation) -> Unit): Long =
        observationStore.replaceListener(listener)

    /** Clears only the listener generation owned by the calling Activity. */
    fun clearObservationListener(generation: Long) {
        observationStore.clearListener(generation)
    }

    /**
     * Legacy explicit Search parity: cancel stale schedulers and read deviceList
     * exactly once for this button press. No retry timer is created.
     */
    fun refreshUsb(): UsbManualScanPrompt? {
        if (!started) return null
        cancelStartupScan()
        stopModeSwitchWatch()
        pendingManualConfirmation = null

        val inventory = readUsbInventory()
        val decision = UsbManualScanPolicy.decide(
            current = currentCandidate,
            devices = inventory.descriptors,
        )
        diagnostics.event(
            "INFO",
            "USB_MANUAL_SCAN",
            "devices=${decision.physicalDeviceCount} candidates=${decision.compatibleCandidateCount}",
        )
        return handleManualDecision(decision, inventory)
    }

    /**
     * Revalidates a chooser selection against one fresh device-list snapshot
     * before the existing permission path may advance it.
     */
    fun chooseManualCandidate(stableKey: String): UsbManualScanPrompt? {
        if (!started) return null
        stopModeSwitchWatch()
        pendingManualConfirmation = null

        val inventory = readUsbInventory()
        val decision = UsbManualScanPolicy.decideChosen(
            stableKey = stableKey,
            devices = inventory.descriptors,
        )
        if (decision == null) {
            diagnostics.event("WARN", "USB_MANUAL_SELECTION_STALE", "stableKey=$stableKey")
            publishInventoryState(inventory)
            return null
        }
        diagnostics.event(
            "INFO",
            "USB_MANUAL_SELECTION_REVALIDATED",
            "stableKey=$stableKey devices=${decision.physicalDeviceCount}",
        )
        return handleManualDecision(decision, inventory)
    }

    /** Continues only the coordinator-owned generic candidate awaiting confirmation. */
    fun confirmManualGenericFastboot(stableKey: String) {
        if (!started) return
        stopModeSwitchWatch()
        val pending = pendingManualConfirmation
        if (pending == null || pending.candidate.stableKey != stableKey) {
            diagnostics.event("WARN", "USB_MANUAL_GENERIC_CONFIRMATION_STALE", "stableKey=$stableKey")
            return
        }
        pendingManualConfirmation = null
        diagnostics.event("INFO", "USB_MANUAL_GENERIC_CONFIRMED", pending.candidate.summary())
        requestAccess(
            candidate = pending.candidate,
            automatic = false,
            observedDevice = pending.device,
            physicalDeviceCount = pending.physicalDeviceCount,
            compatibleCandidateCount = pending.compatibleCandidateCount,
        )
    }

    fun cancelManualUsbPrompt() {
        pendingManualConfirmation = null
    }

    private fun unregisterReceiversSafely() {
        if (permissionReceiverRegistered) {
            permissionReceiverRegistered = false
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
                .onFailure { error ->
                    diagnostics.event(
                        "WARN",
                        "USB_PERMISSION_RECEIVER_UNREGISTER_FAILED",
                        error.javaClass.simpleName,
                    )
                }
        }
        if (detachReceiverRegistered) {
            detachReceiverRegistered = false
            runCatching { appContext.unregisterReceiver(detachReceiver) }
                .onFailure { error ->
                    diagnostics.event(
                        "WARN",
                        "USB_DETACH_RECEIVER_UNREGISTER_FAILED",
                        error.javaClass.simpleName,
                    )
                }
        }
    }

    private fun registerPermissionReceiver(permissionAction: String) {
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

    fun suggestedDiagnosticsArchiveFileName(): String =
        UsbDiagnosticsZipExporter.suggestedFileName()

    fun exportDiagnosticsArchive(output: OutputStream): UsbDiagnosticsZipExporter.Result =
        diagnostics.exportArchive(output)

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
        val inventory = readUsbInventory()
        val allCandidates = UsbInterfaceSelector.findAllCandidates(
            devices = inventory.descriptors,
            includeGenericFastboot = true,
        )
        val candidate = UsbSessionLifecyclePolicy.selectStartupCandidate(
            currentPhase(),
            inventory.descriptors,
        )
        if (candidate == null) {
            diagnostics.event(
                "INFO",
                "USB_STARTUP_SCAN_NO_UNIQUE_CANDIDATE",
                "devices=${inventory.descriptors.size} candidates=${allCandidates.size}",
            )
            if (currentCandidate == null) {
                publishInventoryState(inventory, allCandidates.size)
            } else {
                publishCurrentCandidateReady(
                    physicalDeviceCount = inventory.devices.size,
                    compatibleCandidateCount = allCandidates.size,
                )
            }
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
        requestAccess(
            candidate = candidate,
            automatic = true,
            observedDevice = inventory.deviceFor(candidate),
            physicalDeviceCount = inventory.devices.size,
            compatibleCandidateCount = allCandidates.size,
        )
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
            if (currentCandidate == null) {
                observationStore.publish(
                    UsbSessionObservation(
                        status = UsbSessionObservation.Status.UNSUPPORTED_DEVICE,
                        physicalDeviceCount = 1,
                        compatibleCandidateCount = 0,
                    ),
                )
            }
            return
        }
        requestAccess(
            candidate = candidate,
            automatic = true,
            observedDevice = device,
            physicalDeviceCount = 1,
            compatibleCandidateCount = 1,
        )
    }

    private fun requestAccess(
        candidate: Candidate,
        automatic: Boolean,
        observedDevice: UsbDevice?,
        physicalDeviceCount: Int,
        compatibleCandidateCount: Int,
    ) {
        if (!started) return
        val permissionAction = activePermissionCallback?.action ?: return
        val device = observedDevice
        if (device == null) {
            diagnostics.event("WARN", "USB_CANDIDATE_DISAPPEARED", candidate.summary())
            publishPermissionFailure(UsbSessionObservation.Status.PERMISSION_ERROR)
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
            is UsbPermissionPolicy.Action.Connect -> acceptPermittedCandidate(
                candidate = action.candidate,
                automatic = action.automatic,
                observedDevice = device,
                physicalDeviceCount = physicalDeviceCount,
                compatibleCandidateCount = compatibleCandidateCount,
            )
            is UsbPermissionPolicy.Action.RequestPermission -> {
                observationStore.publish(
                    UsbSessionObservation(
                        status = UsbSessionObservation.Status.PERMISSION_PENDING,
                        physicalDeviceCount = physicalDeviceCount,
                        compatibleCandidateCount = compatibleCandidateCount,
                        candidate = action.candidate.toUsbCandidateSummary(),
                    ),
                )
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
                acceptPermittedCandidate(
                    candidate = action.candidate,
                    automatic = action.automatic,
                    observedDevice = device,
                )
            }
            UsbPermissionPolicy.Action.PermissionDenied -> {
                diagnostics.event("WARN", "USB_PERMISSION_DENIED")
                publishPermissionFailure(UsbSessionObservation.Status.PERMISSION_DENIED)
            }
            UsbPermissionPolicy.Action.MissingDevice -> {
                diagnostics.event("ERROR", "USB_PERMISSION_RESULT_MISSING_DEVICE")
                publishPermissionFailure(UsbSessionObservation.Status.PERMISSION_ERROR)
            }
            UsbPermissionPolicy.Action.NoCandidate -> {
                diagnostics.event("WARN", "USB_PERMISSION_RESULT_NO_CANDIDATE")
                publishPermissionFailure(UsbSessionObservation.Status.PERMISSION_ERROR)
            }
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
                publishPermissionFailure(UsbSessionObservation.Status.PERMISSION_ERROR)
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

    private fun acceptPermittedCandidate(
        candidate: Candidate,
        automatic: Boolean,
        observedDevice: UsbDevice?,
        physicalDeviceCount: Int = observationStore.current().physicalDeviceCount,
        compatibleCandidateCount: Int = observationStore.current().compatibleCandidateCount,
    ) {
        if (currentCandidate?.stableKey == candidate.stableKey) {
            when {
                candidate.mode == Mode.ADB &&
                    !automatic &&
                    failedAdbStableKey == candidate.stableKey &&
                    observedDevice != null -> {
                    diagnostics.event("INFO", "ADB_MANUAL_RETRY", candidate.summary())
                    startAdbTransport(candidate, observedDevice, physicalDeviceCount, compatibleCandidateCount)
                }

                candidate.mode == Mode.FASTBOOT &&
                    !automatic &&
                    failedFastbootStableKey == candidate.stableKey &&
                    observedDevice != null -> {
                    diagnostics.event("INFO", "FASTBOOT_MANUAL_RETRY", candidate.summary())
                    startFastbootTransport(candidate, observedDevice, physicalDeviceCount, compatibleCandidateCount)
                }

                else -> {
                    diagnostics.event("INFO", "USB_DUPLICATE_CANDIDATE_IGNORED", candidate.summary())
                    publishCurrentCandidateReady(physicalDeviceCount, compatibleCandidateCount)
                }
            }
            return
        }

        stopAdbTransport("candidate_changed")
        stopFastbootTransport("candidate_changed")
        currentCandidate = candidate
        failedAdbStableKey = null
        failedFastbootStableKey = null
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
            "sessionId=$sessionId ${candidate.summary()} transport=${when (candidate.mode) { Mode.ADB -> "adb-pending"; Mode.FASTBOOT -> "fastboot-readonly-pending" }}",
        )
        observationStore.publish(
            UsbSessionObservation(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                physicalDeviceCount = physicalDeviceCount,
                compatibleCandidateCount = compatibleCandidateCount,
                candidate = candidate.toUsbCandidateSummary(),
            ),
        )

        when (candidate.mode) {
            Mode.ADB -> {
                if (observedDevice == null) {
                    failedAdbStableKey = candidate.stableKey
                    publishAdbTransport(AdbTransportObservation.Status.ERROR)
                    diagnostics.event("ERROR", "ADB_CONNECT_DEVICE_MISSING", candidate.summary())
                } else {
                    startAdbTransport(candidate, observedDevice, physicalDeviceCount, compatibleCandidateCount)
                }
            }

            Mode.FASTBOOT -> {
                if (observedDevice == null) {
                    failedFastbootStableKey = candidate.stableKey
                    publishFastbootTransport(FastbootTransportObservation.Status.ERROR)
                    diagnostics.event("ERROR", "FASTBOOT_CONNECT_DEVICE_MISSING", candidate.summary())
                } else {
                    startFastbootTransport(candidate, observedDevice, physicalDeviceCount, compatibleCandidateCount)
                }
            }
        }
    }

    private fun startAdbTransport(
        candidate: Candidate,
        device: UsbDevice,
        physicalDeviceCount: Int,
        compatibleCandidateCount: Int,
    ) {
        val generation = adbTransportGeneration.incrementAndGet()
        val previous = synchronized(adbTransportLock) {
            activeAdbTransport.also { activeAdbTransport = null }
        }
        previous?.close()
        failedAdbStableKey = null
        observationStore.publish(
            UsbSessionObservation(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                physicalDeviceCount = physicalDeviceCount,
                compatibleCandidateCount = compatibleCandidateCount,
                candidate = candidate.toUsbCandidateSummary(),
                adbTransport = AdbTransportObservation(AdbTransportObservation.Status.CONNECTING),
            ),
        )
        diagnostics.event("INFO", "ADB_CONNECT_STARTED", "generation=$generation ${candidate.summary()}")

        adbTransportExecutor.execute {
            if (adbTransportGeneration.get() != generation) return@execute
            val transport = AdbUsbTransport(
                usbManager = usbManager,
                device = device,
                keyDirectory = java.io.File(appContext.filesDir, "adbkeys"),
                preferredInterfaceIndex = candidate.interfaceIndex,
                onEvent = { name, detail -> diagnostics.event("INFO", name, detail) },
                onAuthRequired = {
                    handler.post {
                        if (isCurrentAdbGeneration(generation, candidate.stableKey)) {
                            publishAdbTransport(AdbTransportObservation.Status.AUTHORIZING)
                        }
                    }
                },
                onTransportFailure = { code, message ->
                    handler.post {
                        if (isCurrentAdbGeneration(generation, candidate.stableKey)) {
                            failedAdbStableKey = candidate.stableKey
                            publishAdbTransport(AdbTransportObservation.Status.ERROR)
                            diagnostics.event(
                                "ERROR",
                                "ADB_TRANSPORT_FAILED",
                                "generation=$generation code=${code.name} message=${message.take(500)} autoRetry=false",
                            )
                        }
                    }
                },
            )
            val installed = synchronized(adbTransportLock) {
                if (adbTransportGeneration.get() == generation) {
                    activeAdbTransport = transport
                    true
                } else {
                    false
                }
            }
            if (!installed) {
                transport.close()
                return@execute
            }

            val info = transport.connect()
            handler.post {
                if (!isCurrentAdbGeneration(generation, candidate.stableKey)) return@post
                if (info == null || !transport.isConnected) {
                    failedAdbStableKey = candidate.stableKey
                    publishAdbTransport(AdbTransportObservation.Status.ERROR)
                    diagnostics.event(
                        "ERROR",
                        "ADB_CONNECT_ENDED",
                        "generation=$generation success=false autoRetry=false",
                    )
                } else {
                    failedAdbStableKey = null
                    publishAdbTransport(
                        status = AdbTransportObservation.Status.CONNECTED,
                        peerMode = info.peerMode.toObservedPeerMode(),
                    )
                    diagnostics.event(
                        "INFO",
                        "ADB_CONNECT_ENDED",
                        "generation=$generation success=true peerMode=${info.peerMode.name}",
                    )
                    scheduleAdbReadOnlyProbe(generation, transport, info)
                }
            }
        }
    }

    private fun scheduleAdbReadOnlyProbe(
        generation: Long,
        transport: AdbUsbTransport,
        info: AdbUsbTransport.ConnectionInfo,
    ) {
        when (info.peerMode) {
            AdbUsbTransport.PeerMode.SIDELOAD,
            AdbUsbTransport.PeerMode.UNKNOWN -> {
                diagnostics.event(
                    "INFO",
                    "ADB_READ_ONLY_PROBE_SKIPPED",
                    "generation=$generation peerMode=${info.peerMode.name}",
                )
                return
            }

            AdbUsbTransport.PeerMode.DEVICE,
            AdbUsbTransport.PeerMode.RECOVERY -> Unit
        }

        adbTransportExecutor.execute {
            if (!isActiveAdbTransport(generation, transport)) return@execute
            val probe = transport.runProductDeviceReadOnlyProbe()
            if (!isActiveAdbTransport(generation, transport)) {
                diagnostics.event(
                    "INFO",
                    "ADB_READ_ONLY_PROBE_ABORTED",
                    "generation=$generation activeTransportChanged=true",
                )
                return@execute
            }
            diagnostics.event(
                if (probe.success) "INFO" else "ERROR",
                "ADB_READ_ONLY_PROBE_RESULT",
                "generation=$generation success=${probe.success} " +
                    "value=${probe.value.orEmpty().take(200)} detail=${probe.detail.take(500)}",
            )
        }
    }

    private fun isActiveAdbTransport(generation: Long, transport: AdbUsbTransport): Boolean =
        adbTransportGeneration.get() == generation &&
            synchronized(adbTransportLock) { activeAdbTransport === transport }

    private fun stopAdbTransport(reason: String) {
        adbTransportGeneration.incrementAndGet()
        failedAdbStableKey = null
        val transport = synchronized(adbTransportLock) {
            activeAdbTransport.also { activeAdbTransport = null }
        }
        if (transport != null) {
            diagnostics.event("INFO", "ADB_TRANSPORT_STOP", "reason=$reason")
            transport.close()
        }
    }

    private fun isCurrentAdbGeneration(generation: Long, stableKey: String): Boolean =
        started &&
            adbTransportGeneration.get() == generation &&
            currentCandidate?.stableKey == stableKey

    private fun publishAdbTransport(
        status: AdbTransportObservation.Status,
        peerMode: AdbObservedPeerMode? = null,
    ) {
        val candidate = currentCandidate ?: return
        observationStore.publish(
            observationStore.current().copy(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                candidate = candidate.toUsbCandidateSummary(),
                adbTransport = AdbTransportObservation(status, peerMode),
            ),
        )
    }

    private fun startFastbootTransport(
        candidate: Candidate,
        device: UsbDevice,
        physicalDeviceCount: Int,
        compatibleCandidateCount: Int,
    ) {
        val generation = fastbootTransportGeneration.incrementAndGet()
        val previous = synchronized(fastbootTransportLock) {
            activeFastbootTransport.also { activeFastbootTransport = null }
        }
        previous?.close()
        failedFastbootStableKey = null
        observationStore.publish(
            UsbSessionObservation(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                physicalDeviceCount = physicalDeviceCount,
                compatibleCandidateCount = compatibleCandidateCount,
                candidate = candidate.toUsbCandidateSummary(),
                fastbootTransport = FastbootTransportObservation(FastbootTransportObservation.Status.CONNECTING),
            ),
        )
        diagnostics.event("INFO", "FASTBOOT_CONNECT_STARTED", "generation=$generation ${candidate.summary()}")

        fastbootTransportExecutor.execute {
            if (fastbootTransportGeneration.get() != generation) return@execute
            val transport = FastbootUsbTransport(
                usbManager = usbManager,
                device = device,
                preferredInterfaceIndex = candidate.interfaceIndex,
                onEvent = { name, detail -> diagnostics.event("INFO", name, detail) },
                onTransportFailure = { code, message ->
                    handler.post {
                        if (isCurrentFastbootGeneration(generation, candidate.stableKey)) {
                            failedFastbootStableKey = candidate.stableKey
                            publishFastbootTransport(FastbootTransportObservation.Status.ERROR)
                            diagnostics.event(
                                "ERROR",
                                "FASTBOOT_TRANSPORT_FAILED",
                                "generation=$generation code=${code.name} message=${message.take(500)} autoRetry=false",
                            )
                        }
                    }
                },
            )
            val installed = synchronized(fastbootTransportLock) {
                if (fastbootTransportGeneration.get() == generation) {
                    activeFastbootTransport = transport
                    true
                } else {
                    false
                }
            }
            if (!installed) {
                transport.close()
                return@execute
            }

            val info = transport.connectAndQualify()
            handler.post {
                if (!isCurrentFastbootGeneration(generation, candidate.stableKey)) return@post
                if (info == null || !transport.isConnected) {
                    failedFastbootStableKey = candidate.stableKey
                    publishFastbootTransport(FastbootTransportObservation.Status.ERROR)
                    diagnostics.event(
                        "ERROR",
                        "FASTBOOT_CONNECT_ENDED",
                        "generation=$generation success=false autoRetry=false",
                    )
                } else {
                    failedFastbootStableKey = null
                    publishFastbootTransport(
                        status = FastbootTransportObservation.Status.CONNECTED,
                        product = info.product,
                    )
                    diagnostics.event(
                        "INFO",
                        "FASTBOOT_READ_ONLY_PROBE_RESULT",
                        "generation=$generation success=true command=getvar:product " +
                            "product=${info.product ?: "unreported"} finalType=${info.qualifierFinalType}",
                    )
                    diagnostics.event(
                        "INFO",
                        "FASTBOOT_CORE_DIAGNOSTICS_RESULT",
                        "generation=$generation success=true " +
                            "currentSlot=${info.currentSlot ?: "unreported"} " +
                            "slotCount=${info.slotCount ?: "unreported"} " +
                            "unlocked=${info.unlocked ?: "unreported"} " +
                            "maxDownloadSizeRaw=${info.maxDownloadSizeRaw ?: "unreported"} " +
                            "maxDownloadSizeBytes=${info.maxDownloadSizeBytes ?: "unreported"}",
                    )
                    diagnostics.event(
                        "INFO",
                        "FASTBOOT_CONNECT_ENDED",
                        "generation=$generation success=true product=${info.product ?: "unreported"} " +
                            "coreDiagnostics=true",
                    )
                }
            }
        }
    }

    private fun stopFastbootTransport(reason: String) {
        fastbootTransportGeneration.incrementAndGet()
        failedFastbootStableKey = null
        val transport = synchronized(fastbootTransportLock) {
            activeFastbootTransport.also { activeFastbootTransport = null }
        }
        if (transport != null) {
            diagnostics.event("INFO", "FASTBOOT_TRANSPORT_STOP", "reason=$reason")
            transport.close()
        }
    }

    private fun isCurrentFastbootGeneration(generation: Long, stableKey: String): Boolean =
        started &&
            fastbootTransportGeneration.get() == generation &&
            currentCandidate?.stableKey == stableKey

    private fun publishFastbootTransport(
        status: FastbootTransportObservation.Status,
        product: String? = null,
    ) {
        val candidate = currentCandidate ?: return
        observationStore.publish(
            observationStore.current().copy(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                candidate = candidate.toUsbCandidateSummary(),
                fastbootTransport = FastbootTransportObservation(status, product),
            ),
        )
    }

    private fun AdbUsbTransport.PeerMode.toObservedPeerMode(): AdbObservedPeerMode = when (this) {
        AdbUsbTransport.PeerMode.DEVICE -> AdbObservedPeerMode.DEVICE
        AdbUsbTransport.PeerMode.RECOVERY -> AdbObservedPeerMode.RECOVERY
        AdbUsbTransport.PeerMode.SIDELOAD -> AdbObservedPeerMode.SIDELOAD
        AdbUsbTransport.PeerMode.UNKNOWN -> AdbObservedPeerMode.UNKNOWN
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
        val pendingManual = pendingManualConfirmation
        if (pendingManual != null && UsbSessionLifecyclePolicy.isCurrentDevice(
                pendingManual.candidate.device,
                descriptor,
            )
        ) {
            pendingManualConfirmation = null
        }
        if (!decision.disconnectCurrent) return

        stopAdbTransport("device_detached")
        stopFastbootTransport("device_detached")
        currentCandidate = null
        observationStore.publish(
            UsbSessionObservation(
                status = UsbSessionObservation.Status.SCANNING,
                physicalDeviceCount = 0,
                compatibleCandidateCount = 0,
            ),
        )
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
        val inventory = readUsbInventory()
        val allCandidates = UsbInterfaceSelector.findAllCandidates(
            devices = inventory.descriptors,
            includeGenericFastboot = true,
        )
        val result = UsbSessionLifecyclePolicy.tickModeSwitch(
            watch = watch,
            devices = inventory.descriptors,
        )
        modeSwitchWatch = result.nextWatch
        val candidate = result.candidate
        if (candidate != null) {
            diagnostics.event("INFO", "USB_MODE_SWITCH_CANDIDATE", candidate.summary())
            requestAccess(
                candidate = candidate,
                automatic = true,
                observedDevice = inventory.deviceFor(candidate),
                physicalDeviceCount = inventory.devices.size,
                compatibleCandidateCount = allCandidates.size,
            )
            return
        }
        val delayMs = result.scheduleNextAfterMs
        if (delayMs != null && result.nextWatch != null) {
            observationStore.publish(
                UsbSessionObservation(
                    status = UsbSessionObservation.Status.SCANNING,
                    physicalDeviceCount = inventory.devices.size,
                    compatibleCandidateCount = allCandidates.size,
                ),
            )
            handler.postDelayed(modeSwitchRunnable, delayMs)
        } else {
            diagnostics.event("INFO", "USB_MODE_SWITCH_WATCH_FINISHED")
            publishInventoryState(inventory, allCandidates.size)
        }
    }

    private fun handleManualDecision(
        decision: UsbManualScanDecision,
        inventory: UsbInventorySnapshot,
    ): UsbManualScanPrompt? = when (decision) {
        is UsbManualScanDecision.NoCandidate -> {
            diagnostics.event(
                "ERROR",
                "USB_MANUAL_SCAN_NO_COMPATIBLE",
                "devices=${decision.physicalDeviceCount}",
            )
            publishManualInventoryForTroubleshooting(inventory)
            publishInventoryState(inventory, compatibleCandidateCount = 0)
            null
        }

        is UsbManualScanDecision.PreserveCurrent -> {
            publishCurrentCandidateReady(
                physicalDeviceCount = decision.physicalDeviceCount,
                compatibleCandidateCount = decision.compatibleCandidateCount,
            )
            null
        }

        is UsbManualScanDecision.Select -> {
            diagnostics.event("INFO", "USB_MANUAL_CANDIDATE_SELECTED", decision.candidate.summary())
            val device = inventory.deviceFor(decision.candidate)
            if (device == null) {
                diagnostics.event(
                    "WARN",
                    "USB_MANUAL_SELECTION_DISAPPEARED",
                    decision.candidate.summary(),
                )
                publishInventoryState(inventory)
            } else {
                requestAccess(
                    candidate = decision.candidate,
                    automatic = false,
                    observedDevice = device,
                    physicalDeviceCount = decision.physicalDeviceCount,
                    compatibleCandidateCount = decision.compatibleCandidateCount,
                )
            }
            null
        }

        is UsbManualScanDecision.ConfirmGenericFastboot -> {
            diagnostics.event("INFO", "USB_MANUAL_CANDIDATE_SELECTED", decision.candidate.summary())
            val device = inventory.deviceFor(decision.candidate)
            if (device == null) {
                diagnostics.event(
                    "WARN",
                    "USB_MANUAL_GENERIC_DISAPPEARED",
                    decision.candidate.summary(),
                )
                publishInventoryState(inventory)
                null
            } else {
                pendingManualConfirmation = PendingManualConfirmation(
                    candidate = decision.candidate,
                    device = device,
                    physicalDeviceCount = decision.physicalDeviceCount,
                    compatibleCandidateCount = decision.compatibleCandidateCount,
                )
                diagnostics.event(
                    "INFO",
                    "USB_MANUAL_GENERIC_CONFIRMATION_REQUIRED",
                    decision.candidate.summary(),
                )
                UsbManualScanPrompt.ConfirmGenericFastboot(
                    decision.candidate.toUsbCandidateSummary(),
                )
            }
        }

        is UsbManualScanDecision.Choose -> {
            if (currentCandidate == null) {
                observationStore.publish(
                    UsbSessionObservation(
                        status = UsbSessionObservation.Status.MULTIPLE_CANDIDATES,
                        physicalDeviceCount = decision.physicalDeviceCount,
                        compatibleCandidateCount = decision.compatibleCandidateCount,
                    ),
                )
            } else {
                publishCurrentCandidateReady(
                    physicalDeviceCount = decision.physicalDeviceCount,
                    compatibleCandidateCount = decision.compatibleCandidateCount,
                )
            }
            UsbManualScanPrompt.Choose(decision.candidates.map(Candidate::toUsbCandidateSummary))
        }
    }

    private fun publishManualInventoryForTroubleshooting(inventory: UsbInventorySnapshot) {
        if (inventory.descriptors.isEmpty()) {
            diagnostics.event("INFO", "USB_MANUAL_INVENTORY_EMPTY")
            return
        }
        inventory.descriptors.forEach { descriptor ->
            diagnostics.event(
                "INFO",
                "USB_MANUAL_INVENTORY_DEVICE",
                descriptor.manualInventorySummary(),
            )
        }
    }

    private fun UsbDeviceDescriptor.manualInventorySummary(): String {
        val interfaces = interfaces.joinToString(separator = ";") { usbInterface ->
            val endpoints = usbInterface.endpoints.joinToString(separator = ",") { endpoint ->
                "${endpoint.address}/${endpoint.direction.name}/${endpoint.transferType.name}/${endpoint.maxPacketSize}"
            }
            "${usbInterface.id}:${usbInterface.interfaceClass}/${usbInterface.interfaceSubclass}/" +
                "${usbInterface.interfaceProtocol}[$endpoints]"
        }
        return "deviceId=$deviceId deviceName=$deviceName vid=$vendorId pid=$productId " +
            "interfaces=${this.interfaces.size} descriptors=$interfaces"
    }

    private fun publishInventoryState(
        inventory: UsbInventorySnapshot,
        compatibleCandidateCount: Int = UsbInterfaceSelector.findAllCandidates(
            devices = inventory.descriptors,
            includeGenericFastboot = true,
        ).size,
    ) {
        if (currentCandidate != null) {
            publishCurrentCandidateReady(
                physicalDeviceCount = inventory.devices.size,
                compatibleCandidateCount = compatibleCandidateCount,
            )
            return
        }
        val status = when {
            inventory.devices.isEmpty() -> UsbSessionObservation.Status.NO_DEVICE
            compatibleCandidateCount == 0 -> UsbSessionObservation.Status.UNSUPPORTED_DEVICE
            compatibleCandidateCount > 1 -> UsbSessionObservation.Status.MULTIPLE_CANDIDATES
            // A single compatible descriptor can remain after a stale chooser
            // selection or an exhausted mode-switch watch. It is compatible,
            // but it is not the coordinator-owned detected generation.
            else -> UsbSessionObservation.Status.NO_DEVICE
        }
        observationStore.publish(
            UsbSessionObservation(
                status = status,
                physicalDeviceCount = inventory.devices.size,
                compatibleCandidateCount = compatibleCandidateCount,
            ),
        )
    }

    private fun publishCurrentCandidateReady(
        physicalDeviceCount: Int = observationStore.current().physicalDeviceCount,
        compatibleCandidateCount: Int = observationStore.current().compatibleCandidateCount,
    ) {
        val candidate = currentCandidate ?: return
        observationStore.publish(
            UsbSessionObservation(
                status = UsbSessionObservation.Status.CANDIDATE_READY,
                physicalDeviceCount = physicalDeviceCount,
                compatibleCandidateCount = compatibleCandidateCount,
                candidate = candidate.toUsbCandidateSummary(),
                adbTransport = observationStore.current().adbTransport.takeIf { candidate.mode == Mode.ADB }
                    ?: AdbTransportObservation(),
                fastbootTransport = observationStore.current().fastbootTransport.takeIf { candidate.mode == Mode.FASTBOOT }
                    ?: FastbootTransportObservation(),
            ),
        )
    }

    private fun publishPermissionFailure(status: UsbSessionObservation.Status) {
        if (currentCandidate != null) {
            publishCurrentCandidateReady()
            return
        }
        observationStore.publish(
            observationStore.current().copy(
                status = status,
                candidate = null,
            ),
        )
    }

    private fun currentPhase(): UsbSessionLifecyclePolicy.ConnectionPhase = when (currentCandidate?.mode) {
        Mode.ADB -> UsbSessionLifecyclePolicy.ConnectionPhase.ADB
        Mode.FASTBOOT -> UsbSessionLifecyclePolicy.ConnectionPhase.FASTBOOT
        null -> UsbSessionLifecyclePolicy.ConnectionPhase.NONE
    }

    private fun readUsbInventory(): UsbInventorySnapshot {
        val devices = usbManager.deviceList.values.toList()
        return UsbInventorySnapshot(
            devices = devices,
            descriptors = devices.map(AndroidUsbDescriptorMapper::map),
        )
    }

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

    private data class UsbInventorySnapshot(
        val devices: List<UsbDevice>,
        val descriptors: List<UsbDeviceDescriptor>,
    ) {
        fun deviceFor(candidate: Candidate): UsbDevice? = devices.firstOrNull {
            it.deviceId == candidate.device.deviceId && it.deviceName == candidate.device.deviceName
        } ?: devices.firstOrNull {
            it.deviceName == candidate.device.deviceName &&
                it.vendorId == candidate.device.vendorId &&
                it.productId == candidate.device.productId
        }
    }

    private data class PendingManualConfirmation(
        val candidate: Candidate,
        val device: UsbDevice,
        val physicalDeviceCount: Int,
        val compatibleCandidateCount: Int,
    )

    private companion object {
        const val EXTRA_USB_INTENT_CONSUMED = "nekoflash_a2_usb_intent_consumed"
    }
}
