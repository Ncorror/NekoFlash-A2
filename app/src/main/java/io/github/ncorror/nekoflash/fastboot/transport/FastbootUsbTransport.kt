package io.github.ncorror.nekoflash.fastboot.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * First A2 Fastboot transport slice: open/claim and one fixed read-only
 * `getvar:product` peer qualification.
 *
 * The timing and response rules are migrated from the supplied legacy
 * FastbootProtocol. This class intentionally exposes no generic command API and no
 * DATA/mutation path. A failed generation is closed and must be retried explicitly by
 * the coordinator; this class never reopens the device on its own.
 */
class FastbootUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val preferredInterfaceIndex: Int?,
    private val onEvent: (String, String) -> Unit,
    private val onTransportFailure: (FailureCode, String) -> Unit,
) {
    data class ConnectionInfo(
        val product: String?,
        val qualifierFinalType: String,
        val qualifierFinalPayload: String,
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

    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var fastbootInterface: UsbInterface? = null

    @Volatile
    private var closed = false

    @Volatile
    private var failureReported = false

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
            readProductQualification()
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

    private fun readPacket(timeoutMs: Int): FastbootReadOnlySession.Packet? {
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
        event(
            "FASTBOOT_RESPONSE",
            "type=${packet.type} bytes=$bytesRead payload=${packet.payload.take(EVENT_PAYLOAD_LIMIT)}",
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

    private fun fail(code: FailureCode, message: String): ConnectionInfo? {
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
