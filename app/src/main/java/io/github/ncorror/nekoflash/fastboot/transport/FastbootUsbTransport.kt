package io.github.ncorror.nekoflash.fastboot.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * A2 Fastboot read-only transport: fixed `getvar:product` qualification followed by
 * closed core and extended diagnostic sets migrated from legacy refreshDiagnostics().
 *
 * Timing and response rules are migrated from the supplied legacy FastbootProtocol.
 * This class intentionally exposes no generic command API and no DATA/mutation path.
 * A failed generation is closed and must be retried explicitly by the coordinator;
 * this class never reopens the device on its own.
 */
class FastbootUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val preferredInterfaceIndex: Int?,
    private val onEvent: (String, String) -> Unit,
    private val onTransportFailure: (FailureCode, String) -> Unit,
) {
    data class PartitionInventorySummary(
        val productReported: Boolean,
        val topology: String,
        val currentSlot: String?,
        val entryCount: Int,
        val slotFamilyCount: Int,
        val physicalCount: Int,
        val logicalCount: Int,
        val unknownStorageCount: Int,
        val normalCount: Int,
        val advancedCount: Int,
        val criticalCount: Int,
        val incompleteEntryCount: Int,
        val warningCount: Int,
        val duplicateMetadataCount: Int,
        val pointQueryCount: Int,
        val unresolvedPointQueryCount: Int,
        val complete: Boolean,
    )

    data class ManualGetVarAllSummary(
        val supported: Boolean,
        val complete: Boolean,
        val finalStatus: String,
        val variableCount: Int,
        val partitionMetadataCount: Int,
        val ignoredLineCount: Int,
        val duplicateVariableCount: Int,
        val conflictingDuplicateCount: Int,
        val serialReported: Boolean,
        val inventory: PartitionInventorySummary? = null,
    )

    data class ConnectionInfo(
        val product: String?,
        val qualifierFinalType: String,
        val qualifierFinalPayload: String,
        val currentSlot: String? = null,
        val slotCount: String? = null,
        val unlocked: String? = null,
        val maxDownloadSizeRaw: String? = null,
        val maxDownloadSizeBytes: Long? = null,
        val slotSuffix: String? = null,
        val secure: String? = null,
        val serialReported: Boolean = false,
        val versionBootloader: String? = null,
        val antiRollback: String? = null,
        val antiRollbackSource: String? = null,
        val isUserspace: String? = null,
        val superPartitionName: String? = null,
        val snapshotUpdateStatus: String? = null,
        val maxFetchSizeRaw: String? = null,
        val maxFetchSizeBytes: Long? = null,
    )

    enum class FailureCode {
        INTERFACE_NOT_FOUND,
        ENDPOINTS_NOT_FOUND,
        OPEN_FAILED,
        CLAIM_FAILED,
        COMMAND_SHORT_WRITE,
        RESPONSE_TIMEOUT,
        UNEXPECTED_RESPONSE,
        INTERRUPTED,
        CLOSED,
        UNKNOWN,
    }

    private data class MutablePartitionPointProbe(
        val name: String,
        var sizeBytes: Long? = null,
        var type: String? = null,
        var logical: Boolean? = null,
        var hasSlot: Boolean? = null,
        val attemptedFields: MutableSet<FastbootGetVarAllPlan.MetadataField> = linkedSetOf(),
        val resolvedFields: MutableSet<FastbootGetVarAllPlan.MetadataField> = linkedSetOf(),
    ) {
        fun snapshot(): FastbootPartitionInventory.PointProbe = FastbootPartitionInventory.PointProbe(
            name = name,
            sizeBytes = sizeBytes,
            type = type,
            logical = logical,
            hasSlot = hasSlot,
            attemptedFields = attemptedFields.toSet(),
            resolvedFields = resolvedFields.toSet(),
        )
    }

    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var fastbootInterface: UsbInterface? = null

    @Volatile
    private var closed = false

    @Volatile
    private var failureReported = false

    @Volatile
    private var lastConnectionInfo: ConnectionInfo? = null

    val isConnected: Boolean
        get() = !closed && connection != null && fastbootInterface != null && endpointIn != null && endpointOut != null

    fun connectAndQualify(): ConnectionInfo? {
        if (closed) return null

        val usbInterface = findSelectedFastbootInterface()
            ?: return fail(FailureCode.INTERFACE_NOT_FOUND, "Fastboot interface not found")
        fastbootInterface = usbInterface
        val endpoints = findBulkEndpoints(usbInterface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            return fail(FailureCode.ENDPOINTS_NOT_FOUND, "Fastboot bulk IN/OUT endpoints not found")
        }

        event(
            "FASTBOOT_USB_INTERFACE",
            "interface=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                "in=0x${endpointIn!!.address.toString(16)} out=0x${endpointOut!!.address.toString(16)}",
        )

        connection = usbManager.openDevice(device)
        if (connection == null) {
            return fail(FailureCode.OPEN_FAILED, "Could not open USB device for Fastboot")
        }
        if (!connection!!.claimInterface(usbInterface, true)) {
            return fail(FailureCode.CLAIM_FAILED, "Could not claim Fastboot interface")
        }
        event("FASTBOOT_INTERFACE_CLAIMED", "interface=${usbInterface.id}")

        return try {
            if (FastbootReadOnlyTiming.HANDSHAKE_SETTLE_MS > 0L) {
                Thread.sleep(FastbootReadOnlyTiming.HANDSHAKE_SETTLE_MS)
            }
            event(
                "FASTBOOT_HANDSHAKE_STARTED",
                "command=${FastbootReadOnlySession.PRODUCT_COMMAND} settleMs=${FastbootReadOnlyTiming.HANDSHAKE_SETTLE_MS} " +
                    "timeoutMs=${FastbootReadOnlyTiming.HANDSHAKE_TIMEOUT_MS}",
            )
            if (!writeFixedProductCommand()) {
                return fail(
                    FailureCode.COMMAND_SHORT_WRITE,
                    "Fastboot getvar:product command was not written completely",
                )
            }
            val qualification = readProductQualification() ?: return null
            val diagnostics = collectCoreDiagnostics() ?: return null
            val extended = collectExtendedDiagnostics() ?: return null
            qualification.copy(
                currentSlot = diagnostics.currentSlot,
                slotCount = diagnostics.slotCount,
                unlocked = diagnostics.unlocked,
                maxDownloadSizeRaw = diagnostics.maxDownloadSizeRaw,
                maxDownloadSizeBytes = diagnostics.maxDownloadSizeBytes,
                slotSuffix = extended.slotSuffix,
                secure = extended.secure,
                serialReported = extended.serialReported,
                versionBootloader = extended.versionBootloader,
                antiRollback = extended.antiRollback,
                antiRollbackSource = extended.antiRollbackSource,
                isUserspace = extended.isUserspace,
                superPartitionName = extended.superPartitionName,
                snapshotUpdateStatus = extended.snapshotUpdateStatus,
                maxFetchSizeRaw = extended.maxFetchSizeRaw,
                maxFetchSizeBytes = extended.maxFetchSizeBytes,
            ).also { lastConnectionInfo = it }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(FailureCode.INTERRUPTED, "Fastboot qualification interrupted")
        } catch (error: Exception) {
            fail(
                FailureCode.UNKNOWN,
                "Fastboot qualification failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    /**
     * Explicit manual-only legacy `getvar:all` snapshot. It is never called during
     * initial connection. Raw response values are kept in-memory only long enough to
     * build an aggregate summary; every packet payload is redacted from diagnostics.
     */
    fun collectManualGetVarAllSnapshot(): ManualGetVarAllSummary? {
        if (closed || !isConnected) {
            event("FASTBOOT_GETVAR_ALL_REJECTED", "reason=not_connected manual=true")
            return null
        }
        event(
            "FASTBOOT_GETVAR_ALL_STARTED",
            "manual=true command=${FastbootGetVarAllPlan.COMMAND} " +
                "commandTimeoutMs=${FastbootGetVarAllPlan.COMMAND_TIMEOUT_MS} " +
                "responseTimeoutMs=${FastbootGetVarAllPlan.RESPONSE_TIMEOUT_MS}",
        )
        return try {
            if (!writeManualGetVarAllCommand()) {
                return fail(
                    FailureCode.COMMAND_SHORT_WRITE,
                    "Fastboot getvar:all command was not written completely",
                )
            }
            readManualGetVarAllSnapshot()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(FailureCode.INTERRUPTED, "Fastboot getvar:all interrupted")
        } catch (error: Exception) {
            fail(
                FailureCode.UNKNOWN,
                "Fastboot getvar:all failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    fun close() {
        if (closed && connection == null) return
        closed = true
        fastbootInterface?.let { usbInterface ->
            runCatching { connection?.releaseInterface(usbInterface) }
        }
        runCatching { connection?.close() }
        connection = null
        endpointIn = null
        endpointOut = null
        fastbootInterface = null
        lastConnectionInfo = null
        event("FASTBOOT_TRANSPORT_CLOSED", "device=${device.deviceName}")
    }

    private fun writeFixedProductCommand(): Boolean {
        val usbConnection = connection ?: return false
        val output = endpointOut ?: return false
        val command = FastbootReadOnlySession.PRODUCT_COMMAND.toByteArray(Charsets.US_ASCII)
        val sent = usbConnection.bulkTransfer(
            output,
            command,
            0,
            command.size,
            FastbootReadOnlyTiming.HANDSHAKE_TIMEOUT_MS,
        )
        event(
            "FASTBOOT_COMMAND_SENT",
            "command=${FastbootReadOnlySession.PRODUCT_COMMAND} sent=$sent expected=${command.size}",
        )
        return sent == command.size
    }

    private fun collectCoreDiagnostics(): FastbootCoreDiagnostics? {
        val values = linkedMapOf<String, String?>()

        for (variable in FastbootCoreDiagnosticsPlan.variables) {
            val result = queryFixedGetVar(variable) ?: return null
            values[variable.name] = result.value
        }

        val maxDownloadSizeRaw = values["max-download-size"]
        val diagnostics = FastbootCoreDiagnostics(
            currentSlot = values["current-slot"],
            slotCount = values["slot-count"],
            unlocked = values["unlocked"],
            maxDownloadSizeRaw = maxDownloadSizeRaw,
            maxDownloadSizeBytes = FastbootCoreDiagnosticsPlan.parseFastbootSize(maxDownloadSizeRaw),
        )
        event(
            "FASTBOOT_CORE_DIAGNOSTICS_COMPLETE",
            "currentSlot=${diagnostics.currentSlot ?: "unreported"} " +
                "slotCount=${diagnostics.slotCount ?: "unreported"} " +
                "unlocked=${diagnostics.unlocked ?: "unreported"} " +
                "maxDownloadSizeRaw=${diagnostics.maxDownloadSizeRaw ?: "unreported"} " +
                "maxDownloadSizeBytes=${diagnostics.maxDownloadSizeBytes ?: "unreported"}",
        )
        return diagnostics
    }

    private fun collectExtendedDiagnostics(): FastbootExtendedDiagnostics? {
        val values = linkedMapOf<String, String?>()

        for (variable in FastbootExtendedDiagnosticsPlan.beforeAnti) {
            val result = queryFixedGetVar(variable) ?: return null
            values[variable.name] = result.value
        }

        val antiPrimary = queryFixedGetVar(FastbootExtendedDiagnosticsPlan.antiPrimary) ?: return null
        values[FastbootExtendedDiagnosticsPlan.antiPrimary.name] = antiPrimary.value
        val antiRollback: String?
        val antiRollbackSource: String?
        if (FastbootExtendedDiagnosticsPlan.shouldQueryAntiRollback(antiPrimary.value)) {
            val fallback = queryFixedGetVar(FastbootExtendedDiagnosticsPlan.antiFallback) ?: return null
            antiRollback = fallback.value
            antiRollbackSource = if (fallback.value.isNullOrBlank()) null else FastbootExtendedDiagnosticsPlan.antiFallback.name
        } else {
            antiRollback = antiPrimary.value
            antiRollbackSource = FastbootExtendedDiagnosticsPlan.antiPrimary.name
        }

        for (variable in FastbootExtendedDiagnosticsPlan.afterAnti) {
            val result = queryFixedGetVar(variable) ?: return null
            values[variable.name] = result.value
        }

        val maxFetchSizeRaw = values["max-fetch-size"]
        val diagnostics = FastbootExtendedDiagnostics(
            slotSuffix = values["slot-suffix"],
            secure = values["secure"],
            serialReported = !values["serialno"].isNullOrBlank(),
            versionBootloader = values["version-bootloader"],
            antiRollback = antiRollback,
            antiRollbackSource = antiRollbackSource,
            isUserspace = values["is-userspace"],
            superPartitionName = values["super-partition-name"],
            snapshotUpdateStatus = values["snapshot-update-status"],
            maxFetchSizeRaw = maxFetchSizeRaw,
            maxFetchSizeBytes = FastbootCoreDiagnosticsPlan.parseFastbootSize(maxFetchSizeRaw),
        )
        event(
            "FASTBOOT_EXTENDED_DIAGNOSTICS_COMPLETE",
            "slotSuffix=${diagnostics.slotSuffix ?: "unreported"} " +
                "secure=${diagnostics.secure ?: "unreported"} " +
                "serialReported=${diagnostics.serialReported} " +
                "versionBootloader=${diagnostics.versionBootloader ?: "unreported"} " +
                "antiRollback=${diagnostics.antiRollback ?: "unreported"} " +
                "antiSource=${diagnostics.antiRollbackSource ?: "unreported"} " +
                "isUserspace=${diagnostics.isUserspace ?: "unreported"} " +
                "superPartitionName=${diagnostics.superPartitionName ?: "unreported"} " +
                "snapshotUpdateStatus=${diagnostics.snapshotUpdateStatus ?: "unreported"} " +
                "maxFetchSizeRaw=${diagnostics.maxFetchSizeRaw ?: "unreported"} " +
                "maxFetchSizeBytes=${diagnostics.maxFetchSizeBytes ?: "unreported"}",
        )
        return diagnostics
    }

    private fun queryFixedGetVar(
        variable: FastbootCoreDiagnosticsPlan.Variable,
    ): FastbootReadOnlyGetVarSession.Decision.Complete? {
        if (closed) return fail(FailureCode.CLOSED, "Fastboot transport closed during read-only diagnostics")
        event(
            "FASTBOOT_DIAGNOSTIC_QUERY_STARTED",
            "command=${variable.command} timeoutMs=${variable.timeoutMs}",
        )
        if (!writeFixedGetVarCommand(variable)) {
            return fail(
                FailureCode.COMMAND_SHORT_WRITE,
                "Fastboot ${variable.command} command was not written completely",
            )
        }
        val result = readFixedGetVar(variable) ?: return null
        event(
            "FASTBOOT_DIAGNOSTIC_QUERY_RESULT",
            "variable=${variable.name} finalType=${result.finalType} " +
                "value=${variable.valueForEvent(result.value)?.take(EVENT_PAYLOAD_LIMIT) ?: "unreported"} " +
                "payload=${variable.payloadForEvent(result.finalPayload).take(EVENT_PAYLOAD_LIMIT)}",
        )
        return result
    }

    private fun writeFixedGetVarCommand(variable: FastbootCoreDiagnosticsPlan.Variable): Boolean {
        val usbConnection = connection ?: return false
        val output = endpointOut ?: return false
        val command = variable.command.toByteArray(Charsets.US_ASCII)
        val sent = usbConnection.bulkTransfer(
            output,
            command,
            0,
            command.size,
            variable.timeoutMs,
        )
        event(
            "FASTBOOT_COMMAND_SENT",
            "command=${variable.command} sent=$sent expected=${command.size}",
        )
        return sent == command.size
    }

    private fun readFixedGetVar(
        variable: FastbootCoreDiagnosticsPlan.Variable,
    ): FastbootReadOnlyGetVarSession.Decision.Complete? {
        val session = FastbootReadOnlyGetVarSession(variable.name)
        val startedNs = System.nanoTime()
        var emptyReads = 0

        while (!closed) {
            val elapsedMs = ((System.nanoTime() - startedNs).coerceAtLeast(0L) / 1_000_000L)
            val remainingMs = variable.timeoutMs.toLong() - elapsedMs
            if (remainingMs <= 0L) {
                return fail(
                    FailureCode.RESPONSE_TIMEOUT,
                    "${variable.command} response timeout after confirmed command send ($emptyReads empty reads)",
                )
            }

            val packet = readPacket(
                FastbootReadOnlyTiming.nextReadTimeoutMs(remainingMs),
                redactPayload = variable.sensitive,
            )
            if (packet == null) {
                emptyReads += 1
                event(
                    "FASTBOOT_IN_EMPTY",
                    "command=${variable.command} failedRead=$emptyReads/${FastbootReadOnlyTiming.MAX_FAILED_READS} " +
                        "elapsedMs=$elapsedMs remainingMs=$remainingMs",
                )
                if (FastbootReadOnlyTiming.shouldFailAfterEmptyRead(emptyReads, elapsedMs)) {
                    return fail(
                        FailureCode.RESPONSE_TIMEOUT,
                        "Fastboot read failed $emptyReads times for ${variable.command} after confirmed command send",
                    )
                }
                if (FastbootReadOnlyTiming.READ_RETRY_DELAY_MS > 0L) {
                    Thread.sleep(FastbootReadOnlyTiming.READ_RETRY_DELAY_MS)
                }
                continue
            }

            when (val decision = session.accept(packet)) {
                FastbootReadOnlyGetVarSession.Decision.Continue -> {
                    event(
                        if (packet.type == "INFO" || packet.type == "TEXT") {
                            "FASTBOOT_INFO"
                        } else {
                            "FASTBOOT_UNEXPECTED_RESPONSE"
                        },
                        "command=${variable.command} type=${packet.type} " +
                            "payload=${variable.payloadForEvent(packet.payload).take(EVENT_PAYLOAD_LIMIT)}",
                    )
                }

                is FastbootReadOnlyGetVarSession.Decision.Complete -> return decision
            }
        }

        return fail(FailureCode.CLOSED, "Fastboot transport closed while waiting for ${variable.command}")
    }

    private fun writeManualGetVarAllCommand(): Boolean {
        val usbConnection = connection ?: return false
        val output = endpointOut ?: return false
        val command = FastbootGetVarAllPlan.COMMAND.toByteArray(Charsets.US_ASCII)
        val sent = usbConnection.bulkTransfer(
            output,
            command,
            0,
            command.size,
            FastbootGetVarAllPlan.COMMAND_TIMEOUT_MS,
        )
        event(
            "FASTBOOT_COMMAND_SENT",
            "command=${FastbootGetVarAllPlan.COMMAND} sent=$sent expected=${command.size} manual=true",
        )
        return sent == command.size
    }

    private fun readManualGetVarAllSnapshot(): ManualGetVarAllSummary? {
        val lines = mutableListOf<String>()
        val startedNs = System.nanoTime()
        var emptyReads = 0

        while (!closed) {
            val elapsedMs = ((System.nanoTime() - startedNs).coerceAtLeast(0L) / 1_000_000L)
            val remainingMs = FastbootGetVarAllPlan.RESPONSE_TIMEOUT_MS.toLong() - elapsedMs
            if (remainingMs <= 0L) {
                return fail(
                    FailureCode.RESPONSE_TIMEOUT,
                    "getvar:all response timeout after confirmed command send " +
                        "($emptyReads empty reads, lines=${lines.size})",
                )
            }

            // getvar:all can contain unique identifiers. Never export packet payloads.
            val packet = readPacket(
                FastbootReadOnlyTiming.nextReadTimeoutMs(remainingMs),
                redactPayload = true,
            )
            if (packet == null) {
                emptyReads += 1
                event(
                    "FASTBOOT_IN_EMPTY",
                    "command=${FastbootGetVarAllPlan.COMMAND} " +
                        "failedRead=$emptyReads/${FastbootGetVarAllPlan.MAX_FAILED_READS} " +
                        "elapsedMs=$elapsedMs remainingMs=$remainingMs manual=true",
                )
                if (
                    emptyReads >= FastbootGetVarAllPlan.MAX_FAILED_READS &&
                    elapsedMs >= FastbootReadOnlyTiming.MIN_PATIENCE_MS
                ) {
                    return fail(
                        FailureCode.RESPONSE_TIMEOUT,
                        "Fastboot read failed $emptyReads times for getvar:all after confirmed command send",
                    )
                }
                if (FastbootReadOnlyTiming.READ_RETRY_DELAY_MS > 0L) {
                    Thread.sleep(FastbootReadOnlyTiming.READ_RETRY_DELAY_MS)
                }
                continue
            }

            emptyReads = 0
            when (packet.type) {
                "INFO", "TEXT" -> {
                    if (packet.payload.isNotBlank()) lines += packet.payload
                    event(
                        "FASTBOOT_GETVAR_ALL_PROGRESS",
                        "type=${packet.type} lines=${lines.size} payloadRedacted=true",
                    )
                }

                "OKAY" -> {
                    if (packet.payload.isNotBlank()) lines += packet.payload
                    val snapshot = FastbootGetVarAllPlan.parse(
                        lines = lines,
                        complete = true,
                        finalStatus = "OKAY",
                    )
                    val summary = buildManualGetVarAllSummary(snapshot)
                    eventGetVarAllComplete(summary)
                    return summary
                }

                "FAIL" -> {
                    if (lines.isEmpty()) {
                        val summary = FastbootGetVarAllPlan.unsupported().toPublicSummary()
                        eventGetVarAllComplete(summary)
                        return summary
                    }
                    val snapshot = FastbootGetVarAllPlan.parse(
                        lines = lines,
                        complete = false,
                        finalStatus = "FAIL",
                        finalMessage = packet.payload,
                    )
                    val summary = buildManualGetVarAllSummary(snapshot)
                    eventGetVarAllComplete(summary)
                    return summary
                }

                else -> event(
                    "FASTBOOT_GETVAR_ALL_UNEXPECTED_RESPONSE",
                    "type=${packet.type} lines=${lines.size} payloadRedacted=true",
                )
            }
        }

        return fail(FailureCode.CLOSED, "Fastboot transport closed while waiting for getvar:all")
    }

    private fun buildManualGetVarAllSummary(
        snapshot: FastbootGetVarAllPlan.Snapshot,
    ): ManualGetVarAllSummary {
        val connectionInfo = lastConnectionInfo
        val supplemental = linkedMapOf<String, String>()
        connectionInfo?.currentSlot?.takeIf { it.isNotBlank() }?.let { supplemental["current-slot"] = it }
        connectionInfo?.slotCount?.takeIf { it.isNotBlank() }?.let { supplemental["slot-count"] = it }

        val initialInventory = FastbootPartitionInventory.from(
            source = snapshot,
            fallbackProduct = connectionInfo?.product,
            supplementalVariables = supplemental,
        )
        val plan = FastbootPartitionProbePlanner.plan(
            source = snapshot,
            inventory = initialInventory,
            maxQueries = FastbootPartitionProbePlanner.MAX_POINT_QUERIES,
        )
        event(
            "FASTBOOT_PARTITION_BACKFILL_PLAN",
            "planned=${plan.requests.size} omitted=${plan.omittedRequestCount} " +
                "budget=${FastbootPartitionProbePlanner.MAX_POINT_QUERIES} " +
                "discoveryFallback=${plan.discoveryFallbackUsed} manual=true privacySafe=true",
        )

        val probes = linkedMapOf<String, MutablePartitionPointProbe>()
        var abortedByBrokenSession = false
        for (request in plan.requests) {
            if (closed || !isConnected) {
                abortedByBrokenSession = true
                break
            }
            val probe = probes.getOrPut(request.partition) {
                MutablePartitionPointProbe(request.partition)
            }
            probe.attemptedFields += request.field
            val result = queryFixedGetVar(
                FastbootCoreDiagnosticsPlan.Variable(request.variableName),
            )
            if (result == null) {
                abortedByBrokenSession = true
                break
            }
            val raw = result.value ?: continue
            when (request.field) {
                FastbootGetVarAllPlan.MetadataField.SIZE -> {
                    FastbootGetVarAllPlan.parseSizeValue(raw)?.let {
                        probe.sizeBytes = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllPlan.MetadataField.TYPE -> {
                    raw.trim().takeIf { it.isNotBlank() }?.let {
                        probe.type = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllPlan.MetadataField.LOGICAL -> {
                    FastbootGetVarAllPlan.parseBooleanValue(raw)?.let {
                        probe.logical = it
                        probe.resolvedFields += request.field
                    }
                }
                FastbootGetVarAllPlan.MetadataField.HAS_SLOT -> {
                    FastbootGetVarAllPlan.parseBooleanValue(raw)?.let {
                        probe.hasSlot = it
                        probe.resolvedFields += request.field
                    }
                }
            }
        }

        val collectionWarnings = mutableListOf<FastbootPartitionInventory.Warning>()
        if (plan.discoveryFallbackUsed) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "LIMITED_POINT_DISCOVERY",
                severity = FastbootPartitionInventory.WarningSeverity.WARNING,
            )
        }
        if (plan.omittedRequestCount > 0) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_BUDGET_EXHAUSTED",
                severity = FastbootPartitionInventory.WarningSeverity.INFO,
            )
        }
        if (abortedByBrokenSession) {
            collectionWarnings += FastbootPartitionInventory.Warning(
                code = "POINT_QUERY_ABORTED",
                severity = FastbootPartitionInventory.WarningSeverity.CRITICAL,
            )
        }

        val inventory = FastbootPartitionInventory.from(
            source = snapshot,
            fallbackProduct = connectionInfo?.product,
            supplementalVariables = supplemental,
            pointProbes = probes.values.map { it.snapshot() },
            collectionWarnings = collectionWarnings,
        ).summary()
        val publicInventory = inventory.toPublicSummary()
        val resolvedPointQueries = inventory.pointQueryCount - inventory.unresolvedPointQueryCount
        event(
            "FASTBOOT_PARTITION_BACKFILL_COMPLETE",
            "planned=${plan.requests.size} attempted=${inventory.pointQueryCount} " +
                "resolved=$resolvedPointQueries unresolved=${inventory.unresolvedPointQueryCount} " +
                "omitted=${plan.omittedRequestCount} aborted=$abortedByBrokenSession " +
                "manual=true privacySafe=true",
        )
        event(
            "FASTBOOT_PARTITION_INVENTORY_COMPLETE",
            "source=getvar_all_plus_bounded_point_queries topology=${publicInventory.topology} " +
                "currentSlot=${publicInventory.currentSlot ?: "unreported"} " +
                "entries=${publicInventory.entryCount} slotFamilies=${publicInventory.slotFamilyCount} " +
                "physical=${publicInventory.physicalCount} logical=${publicInventory.logicalCount} " +
                "unknownStorage=${publicInventory.unknownStorageCount} normal=${publicInventory.normalCount} " +
                "advanced=${publicInventory.advancedCount} critical=${publicInventory.criticalCount} " +
                "incomplete=${publicInventory.incompleteEntryCount} warnings=${publicInventory.warningCount} " +
                "duplicates=${publicInventory.duplicateMetadataCount} complete=${publicInventory.complete} " +
                "pointQueries=${publicInventory.pointQueryCount} " +
                "unresolvedPointQueries=${publicInventory.unresolvedPointQueryCount} privacySafe=true",
        )
        return snapshot.summary().toPublicSummary(publicInventory)
    }

    private fun FastbootGetVarAllPlan.Summary.toPublicSummary(
        inventory: PartitionInventorySummary? = null,
    ): ManualGetVarAllSummary = ManualGetVarAllSummary(
        supported = supported,
        complete = complete,
        finalStatus = finalStatus,
        variableCount = variableCount,
        partitionMetadataCount = partitionMetadataCount,
        ignoredLineCount = ignoredLineCount,
        duplicateVariableCount = duplicateVariableCount,
        conflictingDuplicateCount = conflictingDuplicateCount,
        serialReported = serialReported,
        inventory = inventory,
    )

    private fun FastbootPartitionInventory.Summary.toPublicSummary(): PartitionInventorySummary =
        PartitionInventorySummary(
            productReported = productReported,
            topology = topology.name,
            currentSlot = currentSlot,
            entryCount = entryCount,
            slotFamilyCount = slotFamilyCount,
            physicalCount = physicalCount,
            logicalCount = logicalCount,
            unknownStorageCount = unknownStorageCount,
            normalCount = normalCount,
            advancedCount = advancedCount,
            criticalCount = criticalCount,
            incompleteEntryCount = incompleteEntryCount,
            warningCount = warningCount,
            duplicateMetadataCount = duplicateMetadataCount,
            pointQueryCount = pointQueryCount,
            unresolvedPointQueryCount = unresolvedPointQueryCount,
            complete = complete,
        )

    private fun eventGetVarAllComplete(summary: ManualGetVarAllSummary) {
        event(
            "FASTBOOT_GETVAR_ALL_COMPLETE",
            "manual=true supported=${summary.supported} complete=${summary.complete} " +
                "finalType=${summary.finalStatus} variables=${summary.variableCount} " +
                "partitionMetadata=${summary.partitionMetadataCount} ignored=${summary.ignoredLineCount} " +
                "duplicates=${summary.duplicateVariableCount} " +
                "conflictingDuplicates=${summary.conflictingDuplicateCount} " +
                "serialReported=${summary.serialReported} " +
                "inventoryTopology=${summary.inventory?.topology ?: "unreported"} " +
                "inventoryEntries=${summary.inventory?.entryCount ?: 0} " +
                "pointQueries=${summary.inventory?.pointQueryCount ?: 0} " +
                "unresolvedPointQueries=${summary.inventory?.unresolvedPointQueryCount ?: 0} " +
                "payloadsRedacted=true",
        )
    }

    private fun readProductQualification(): ConnectionInfo? {
        val session = FastbootReadOnlySession()
        val startedNs = System.nanoTime()
        var emptyReads = 0

        while (!closed) {
            val elapsedMs = ((System.nanoTime() - startedNs).coerceAtLeast(0L) / 1_000_000L)
            val remainingMs = FastbootReadOnlyTiming.HANDSHAKE_TIMEOUT_MS.toLong() - elapsedMs
            if (remainingMs <= 0L) {
                return fail(
                    FailureCode.RESPONSE_TIMEOUT,
                    "getvar:product response timeout after confirmed command send ($emptyReads empty reads)",
                )
            }

            val packet = readPacket(FastbootReadOnlyTiming.nextReadTimeoutMs(remainingMs))
            if (packet == null) {
                emptyReads += 1
                event(
                    "FASTBOOT_IN_EMPTY",
                    "command=${FastbootReadOnlySession.PRODUCT_COMMAND} failedRead=$emptyReads/${FastbootReadOnlyTiming.MAX_FAILED_READS} " +
                        "elapsedMs=$elapsedMs remainingMs=$remainingMs",
                )
                if (FastbootReadOnlyTiming.shouldFailAfterEmptyRead(emptyReads, elapsedMs)) {
                    return fail(
                        FailureCode.RESPONSE_TIMEOUT,
                        "Fastboot read failed $emptyReads times for getvar:product after confirmed command send",
                    )
                }
                if (FastbootReadOnlyTiming.READ_RETRY_DELAY_MS > 0L) Thread.sleep(FastbootReadOnlyTiming.READ_RETRY_DELAY_MS)
                continue
            }

            when (val decision = session.accept(packet)) {
                FastbootReadOnlySession.Decision.Continue -> {
                    event(
                        if (packet.type == "INFO" || packet.type == "TEXT") {
                            "FASTBOOT_INFO"
                        } else {
                            "FASTBOOT_UNEXPECTED_RESPONSE"
                        },
                        "type=${packet.type} payload=${packet.payload.take(EVENT_PAYLOAD_LIMIT)}",
                    )
                }

                is FastbootReadOnlySession.Decision.Qualified -> {
                    val safeProduct = decision.product?.take(EVENT_PAYLOAD_LIMIT)
                    event(
                        "FASTBOOT_HANDSHAKE_CONFIRMED",
                        "finalType=${decision.finalType} product=${safeProduct ?: "unreported"} " +
                            "payload=${decision.finalPayload.take(EVENT_PAYLOAD_LIMIT)}",
                    )
                    return ConnectionInfo(
                        product = safeProduct,
                        qualifierFinalType = decision.finalType,
                        qualifierFinalPayload = decision.finalPayload.take(EVENT_PAYLOAD_LIMIT),
                    )
                }
            }
        }

        return fail(FailureCode.CLOSED, "Fastboot transport closed during qualification")
    }

    private fun readPacket(
        timeoutMs: Int,
        redactPayload: Boolean = false,
    ): FastbootReadOnlySession.Packet? {
        val usbConnection = connection ?: return null
        val input = endpointIn ?: return null
        val buffer = ByteArray(FASTBOOT_RESPONSE_BUFFER_BYTES)
        val bytesRead = usbConnection.bulkTransfer(
            input,
            buffer,
            0,
            buffer.size,
            timeoutMs,
        )
        if (bytesRead <= 0) return null

        val packet = FastbootReadOnlySession.parsePacket(buffer, bytesRead)
        val eventPayload = if (redactPayload && packet.payload.isNotBlank()) {
            "<redacted>"
        } else {
            packet.payload.take(EVENT_PAYLOAD_LIMIT)
        }
        event(
            "FASTBOOT_RESPONSE",
            "type=${packet.type} bytes=$bytesRead payload=$eventPayload",
        )
        return packet
    }

    private fun findSelectedFastbootInterface(): UsbInterface? {
        preferredInterfaceIndex?.let { index ->
            if (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (isFastbootCompatibleInterface(usbInterface, allowGeneric = true)) return usbInterface
                event(
                    "FASTBOOT_INTERFACE_REBIND",
                    "preferred=$index no longer matches Fastboot-compatible bulk pair",
                )
            }
        }

        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (isCanonicalFastbootInterface(usbInterface)) return usbInterface
        }
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (isFastbootCompatibleInterface(usbInterface, allowGeneric = false)) return usbInterface
        }
        return null
    }

    private fun isCanonicalFastbootInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == FASTBOOT_PROTOCOL &&
            hasBulkPair(usbInterface)

    private fun isFastbootCompatibleInterface(usbInterface: UsbInterface, allowGeneric: Boolean): Boolean {
        if (!hasBulkPair(usbInterface)) return false
        if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) return false
        if (allowGeneric) return usbInterface.interfaceProtocol != ADB_PROTOCOL
        return usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS && usbInterface.interfaceProtocol != ADB_PROTOCOL
    }

    private fun hasBulkPair(usbInterface: UsbInterface): Boolean =
        findBulkEndpoints(usbInterface).let { it.first != null && it.second != null }

    private fun findBulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
        }
        return input to output
    }

    private fun <T> fail(code: FailureCode, message: String): T? {
        event("FASTBOOT_CONNECT_FAILED", "code=${code.name} message=${message.take(EVENT_PAYLOAD_LIMIT)}")
        if (!failureReported) {
            failureReported = true
            onTransportFailure(code, message)
        }
        close()
        return null
    }

    private fun event(name: String, detail: String) {
        runCatching { onEvent(name, detail) }
    }

    private companion object {
        const val ANDROID_USB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
        const val FASTBOOT_PROTOCOL = 0x03
        const val FASTBOOT_RESPONSE_BUFFER_BYTES = 1024
        const val EVENT_PAYLOAD_LIMIT = 300
    }
}
