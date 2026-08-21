package io.github.ncorror.nekoflash.adb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.ncorror.nekoflash.adb.codec.AdbChecksum
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 6B ADB USB bring-up copied mechanically from the pinned legacy transport contract.
 *
 * Scope is intentionally narrow: open/claim, exactly one CNXN, RSA AUTH, banner parsing,
 * the single-reader dispatcher, and one fixed read-only identity probe copied from the legacy
 * stream framing. No generic shell API exists. The instance is one transport generation;
 * failures never trigger reopen/retry here.
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val keyDirectory: File,
    private val preferredInterfaceIndex: Int?,
    private val onEvent: (String, String) -> Unit,
    private val onAuthRequired: () -> Unit,
    private val onTransportFailure: (AdbPacketDispatcher.FailureCode, String) -> Unit,
) {
    data class ConnectionInfo(
        val remoteBanner: String,
        val peerMode: PeerMode,
        val features: Set<String>,
    )

    data class ReadOnlyProbeResult(
        val success: Boolean,
        val value: String?,
        val detail: String,
    )

    enum class PeerMode {
        DEVICE,
        RECOVERY,
        SIDELOAD,
        UNKNOWN,
    }

    private data class Header(
        val command: Long,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val checksum: Int,
        val magic: Int,
    )

    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var adbInterface: UsbInterface? = null
    private var peerProtocolVersion = LOCAL_ADB_VERSION
    private var pendingInboundChecksum: Int? = null
    private var pendingInboundLength = 0
    private var pendingInboundCommand = 0L
    private val outboundHeaderBuffer = ByteArray(ADB_HEADER_SIZE)
    private val inboundHeaderBuffer = ByteArray(ADB_HEADER_SIZE)
    private val adbWriteLock = Any()
    private val dispatcherGeneration = AtomicLong(0L)
    private val nextLocalStreamId = AtomicInteger(1)

    @Volatile
    private var closed = false

    @Volatile
    private var packetDispatcher: AdbPacketDispatcher? = null

    @Volatile
    private var directReadFailureCode: AdbPacketDispatcher.FailureCode? = null

    @Volatile
    private var directReadFailureMessage: String? = null

    private val adbKeyStore by lazy { AdbKeyStore(keyDirectory) { message -> event("ADB_KEY", message) } }

    val isConnected: Boolean
        get() = !closed &&
            connection != null &&
            packetDispatcher?.snapshot()?.running == true

    fun connect(): ConnectionInfo? {
        if (closed) return null
        peerProtocolVersion = LOCAL_ADB_VERSION
        clearPendingInboundPayload()
        directReadFailureCode = null
        directReadFailureMessage = null

        val usbInterface = findAdbInterface() ?: return failConnect("ADB interface not found")
        adbInterface = usbInterface
        val endpoints = findBulkEndpoints(usbInterface)
        endpointIn = endpoints.first
        endpointOut = endpoints.second
        if (endpointIn == null || endpointOut == null) {
            return failConnect("ADB bulk endpoints not found")
        }

        event(
            "ADB_USB_INTERFACE",
            "interface=${usbInterface.id} in=0x${endpointIn!!.address.toString(16)} " +
                "out=0x${endpointOut!!.address.toString(16)}",
        )

        connection = usbManager.openDevice(device)
        if (connection == null) return failConnect("Could not open USB device for ADB")
        if (!connection!!.claimInterface(usbInterface, true)) {
            return failConnect("Could not claim ADB interface")
        }

        // One physical transport, one CNXN. No automatic close/reopen or second CNXN.
        return try {
            sendMessage(
                command = A_CNXN,
                arg0 = LOCAL_ADB_VERSION,
                arg1 = MAX_PAYLOAD,
                payload = "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8),
            )
            event("ADB_CNXN_SENT", "version=$LOCAL_ADB_VERSION maxPayload=$MAX_PAYLOAD")

            val firstHeader = readHeader() ?: return failConnect("ADB connection failed: no response")
            val info = when (firstHeader.command) {
                A_CNXN -> readConnectionBanner(firstHeader)
                A_AUTH -> handleAuth(firstHeader)
                else -> {
                    consumeUnexpectedPayload(firstHeader)
                    null
                }
            } ?: return failConnect(
                "Unexpected or incomplete ADB handshake response cmd=0x${firstHeader.command.toString(16)}",
            )

            if (!startPacketDispatcher()) {
                return failConnect("Could not start ADB single-reader dispatcher")
            }
            event(
                "ADB_CONNECTED",
                "peerMode=${info.peerMode.name} features=${info.features.size} banner=${info.remoteBanner.take(300)}",
            )
            info
        } catch (error: Exception) {
            failConnect("ADB connection failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun runProductDeviceReadOnlyProbe(): ReadOnlyProbeResult {
        if (!isConnected) {
            return ReadOnlyProbeResult(false, null, "ADB transport is not connected")
        }
        val dispatcher = packetDispatcher
            ?: return ReadOnlyProbeResult(false, null, "ADB packet dispatcher is unavailable")
        val localId = nextLocalStreamId.getAndIncrement().takeIf { it > 0 } ?: 1
        val session = AdbReadOnlyStreamSession(localId)
        val service = AdbReadOnlyStreamSession.READ_ONLY_SERVICE
        event("ADB_READ_ONLY_PROBE_STARTED", "service=$service local=$localId")

        return try {
            sendOutbound(session.openRequest())
            event("ADB_STREAM_OPEN_SENT", "service=$service local=$localId")
            val deadlineNs = System.nanoTime() + READ_ONLY_PROBE_TOTAL_TIMEOUT_MS * 1_000_000L
            while (!closed) {
                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L)
                    .coerceAtMost(READ_ONLY_PROBE_PACKET_TIMEOUT_MS.toLong())
                    .toInt()
                if (remainingMs <= 0) {
                    session.timeoutClosePacket()?.let(::sendOutboundSafely)
                    val detail = "read-only probe timed out service=$service"
                    event("ADB_READ_ONLY_PROBE_FAILED", detail)
                    return ReadOnlyProbeResult(false, null, detail)
                }

                val packet = dispatcher.take(remainingMs)
                if (packet == null) {
                    val snapshot = dispatcher.snapshot()
                    if (!snapshot.running) {
                        val detail = snapshot.lastFailureMessage
                            ?: "ADB packet dispatcher stopped during read-only probe"
                        event("ADB_READ_ONLY_PROBE_FAILED", detail)
                        return ReadOnlyProbeResult(false, null, detail)
                    }
                    continue
                }

                val step = session.consume(packet)
                step.outbound.forEach(::sendOutbound)
                when (step.transition) {
                    AdbReadOnlyStreamSession.Transition.OPENED ->
                        event("ADB_STREAM_OPENED", step.detail)

                    AdbReadOnlyStreamSession.Transition.DATA ->
                        event("ADB_STREAM_DATA", step.detail)

                    AdbReadOnlyStreamSession.Transition.EARLY_DATA_IGNORED ->
                        event("ADB_STREAM_EARLY_DATA", step.detail)

                    AdbReadOnlyStreamSession.Transition.STALE_PACKET ->
                        event("ADB_STREAM_STALE_PACKET", step.detail)

                    AdbReadOnlyStreamSession.Transition.UNEXPECTED_PACKET ->
                        event("ADB_STREAM_UNEXPECTED_PACKET", step.detail)

                    AdbReadOnlyStreamSession.Transition.FAILED -> {
                        val detail = "service=$service ${step.detail}"
                        event("ADB_READ_ONLY_PROBE_FAILED", detail)
                        return ReadOnlyProbeResult(false, null, detail)
                    }

                    AdbReadOnlyStreamSession.Transition.COMPLETED -> {
                        val rawOutput = session.outputText()
                        val value = rawOutput
                            .replace("\r", "")
                            .lineSequence()
                            .map(String::trim)
                            .firstOrNull(String::isNotEmpty)
                        if (value == null) {
                            val detail = "service=$service returned empty output"
                            event("ADB_READ_ONLY_PROBE_FAILED", detail)
                            return ReadOnlyProbeResult(false, null, detail)
                        }
                        val safeValue = value.take(READ_ONLY_PROBE_VALUE_LIMIT)
                        event(
                            "ADB_READ_ONLY_PROBE_SUCCESS",
                            "service=$service value=$safeValue bytes=${rawOutput.toByteArray().size}",
                        )
                        return ReadOnlyProbeResult(true, safeValue, "read-only probe completed")
                    }
                }
            }

            ReadOnlyProbeResult(false, null, "ADB transport closed during read-only probe")
        } catch (error: Exception) {
            session.timeoutClosePacket()?.let(::sendOutboundSafely)
            val detail = "service=$service error=${error.message ?: error.javaClass.simpleName}"
            event("ADB_READ_ONLY_PROBE_FAILED", detail)
            ReadOnlyProbeResult(false, null, detail)
        }
    }

    private fun sendOutbound(packet: AdbReadOnlyStreamSession.OutboundPacket) {
        sendMessage(packet.command, packet.arg0, packet.arg1, packet.payload)
    }

    private fun sendOutboundSafely(packet: AdbReadOnlyStreamSession.OutboundPacket) {
        runCatching { sendOutbound(packet) }
    }

    fun close() {
        if (closed && connection == null && packetDispatcher == null) return
        closed = true
        dispatcherGeneration.incrementAndGet()
        packetDispatcher?.stop()
        packetDispatcher = null
        adbInterface?.let { usbInterface -> runCatching { connection?.releaseInterface(usbInterface) } }
        runCatching { connection?.close() }
        connection = null
        endpointIn = null
        endpointOut = null
        adbInterface = null
        peerProtocolVersion = LOCAL_ADB_VERSION
        clearPendingInboundPayload()
        event("ADB_TRANSPORT_CLOSED", "device=${device.deviceName}")
    }

    private fun handleAuth(firstHeader: Header): ConnectionInfo? {
        if (firstHeader.arg0 != AUTH_TOKEN) {
            consumeUnexpectedPayload(firstHeader)
            event("ADB_AUTH_FAILED", "unsupportedType=${firstHeader.arg0}")
            return null
        }

        val firstToken = readData(firstHeader.dataLength) ?: return null
        event("ADB_AUTH_REQUIRED", "RSA authorization required")
        onAuthRequired()

        var publicKeySent = false
        try {
            sendMessage(A_AUTH, AUTH_SIGNATURE, 0, adbKeyStore.signToken(firstToken))
            event("ADB_AUTH_SIGNATURE_SENT", "saved key attempted")
        } catch (error: Exception) {
            event("ADB_AUTH_SIGNATURE_FAILED", error.message ?: error.javaClass.simpleName)
            publicKeySent = sendPublicKey()
            if (!publicKeySent) return null
        }

        repeat(AUTH_RESPONSE_LIMIT) { attempt ->
            if (closed) return null
            val timeoutMs = if (publicKeySent) AUTH_PUBLIC_KEY_TIMEOUT_MS else AUTH_SIGNATURE_TIMEOUT_MS
            val header = readHeader(timeoutMs) ?: return null
            when (header.command) {
                A_CNXN -> return readConnectionBanner(header)
                A_AUTH -> when (header.arg0) {
                    AUTH_TOKEN -> {
                        if (header.dataLength > 0 && readData(header.dataLength) == null) return null
                        if (!publicKeySent) {
                            publicKeySent = sendPublicKey()
                            if (!publicKeySent) return null
                        } else if (attempt % 3 == 2) {
                            event("ADB_AUTH_WAITING", "waiting for device confirmation")
                        }
                    }

                    else -> {
                        consumeUnexpectedPayload(header)
                        event("ADB_AUTH_FAILED", "unsupportedType=${header.arg0}")
                        return null
                    }
                }

                else -> {
                    consumeUnexpectedPayload(header)
                    event("ADB_AUTH_FAILED", "unexpectedCommand=0x${header.command.toString(16)}")
                    return null
                }
            }
        }
        event("ADB_AUTH_FAILED", "authorization response limit exhausted")
        return null
    }

    private fun sendPublicKey(): Boolean = try {
        sendMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, adbKeyStore.publicKeyPayload())
        event("ADB_AUTH_PUBLIC_KEY_SENT", "path=${adbKeyStore.publicKeyPath()}")
        true
    } catch (error: Exception) {
        event("ADB_AUTH_PUBLIC_KEY_FAILED", error.message ?: error.javaClass.simpleName)
        false
    }

    private fun readConnectionBanner(header: Header): ConnectionInfo? {
        peerProtocolVersion = header.arg0
        val bannerBytes = readData(header.dataLength) ?: if (header.dataLength == 0) EMPTY_PAYLOAD else return null
        val banner = bannerBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        val features = banner
            .split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.substringAfter("features=")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val peerMode = when {
            banner.startsWith("sideload::", ignoreCase = true) -> PeerMode.SIDELOAD
            banner.startsWith("recovery::", ignoreCase = true) -> PeerMode.RECOVERY
            banner.startsWith("device::", ignoreCase = true) -> PeerMode.DEVICE
            else -> PeerMode.UNKNOWN
        }
        event("ADB_BANNER", banner.take(500))
        return ConnectionInfo(banner, peerMode, features)
    }

    private fun findAdbInterface(): UsbInterface? {
        preferredInterfaceIndex?.let { index ->
            if (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (isAdbInterface(usbInterface) && hasBulkPair(usbInterface)) return usbInterface
                event("ADB_INTERFACE_REBIND", "preferred=$index no longer matches; safe search follows")
            }
        }
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (isAdbInterface(usbInterface) && hasBulkPair(usbInterface)) return usbInterface
        }
        return null
    }

    private fun isAdbInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == ADB_PROTOCOL

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

    private fun sendMessage(command: Long, arg0: Int, arg1: Int, payload: ByteArray) = synchronized(adbWriteLock) {
        val header = outboundHeaderBuffer
        putIntLe(header, 0, command.toInt())
        putIntLe(header, 4, arg0)
        putIntLe(header, 8, arg1)
        putIntLe(header, 12, payload.size)
        putIntLe(header, 16, AdbChecksum.compute(payload))
        putIntLe(header, 20, command.inv().toInt())

        if (!bulkWriteFully(header)) error("ADB header transfer error")
        var offset = 0
        while (offset < payload.size) {
            val length = minOf(USB_BULK_CHUNK_BYTES, payload.size - offset)
            if (!bulkWriteFully(payload, offset, length)) error("ADB payload transfer error")
            offset += length
        }
    }

    private fun bulkWriteFully(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        timeoutMs: Int = USB_WRITE_TIMEOUT_MS,
    ): Boolean {
        val usbConnection = connection ?: return false
        val endpoint = endpointOut ?: return false
        var written = 0
        while (written < length) {
            val sent = usbConnection.bulkTransfer(
                endpoint,
                data,
                offset + written,
                length - written,
                timeoutMs,
            )
            if (sent <= 0) {
                clearEndpointHalt(endpoint)
                return false
            }
            written += sent
        }
        return true
    }

    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        val usbConnection = connection ?: return
        runCatching {
            usbConnection.controlTransfer(
                0x02,
                0x01,
                0x00,
                endpoint.address,
                null,
                0,
                500,
            )
        }
    }

    private fun startPacketDispatcher(): Boolean {
        if (packetDispatcher?.snapshot()?.running == true) return true
        val generation = dispatcherGeneration.incrementAndGet()
        lateinit var dispatcher: AdbPacketDispatcher
        dispatcher = AdbPacketDispatcher(
            source = ::readCompletePacketDirect,
            onFailure = { code, message ->
                if (dispatcherGeneration.get() == generation && !closed) {
                    event("ADB_READER_FAILED", "code=${code.name} message=${message.take(500)}")
                    closed = true
                    runCatching { connection?.close() }
                    connection = null
                    onTransportFailure(code, message)
                }
            },
        )
        packetDispatcher = dispatcher
        val started = dispatcher.start()
        if (started) event("ADB_READER_STARTED", "queueCapacity=256")
        return started
    }

    private fun readCompletePacketDirect(timeoutMs: Int): AdbPacketDispatcher.ReadResult {
        if (connection == null || endpointIn == null || closed) return AdbPacketDispatcher.ReadResult.Closed
        directReadFailureCode = null
        directReadFailureMessage = null
        val header = readHeaderDirect(timeoutMs) ?: return directReadFailureCode?.let { code ->
            AdbPacketDispatcher.ReadResult.Failed(
                code,
                directReadFailureMessage ?: "ADB header read failed",
            )
        } ?: if (connection == null || closed) {
            AdbPacketDispatcher.ReadResult.Closed
        } else {
            AdbPacketDispatcher.ReadResult.Timeout
        }
        val payload = if (header.dataLength > 0) {
            readDataDirect(header.dataLength) ?: return AdbPacketDispatcher.ReadResult.Failed(
                directReadFailureCode ?: AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD,
                directReadFailureMessage
                    ?: "ADB payload read/validation failed for cmd=0x${header.command.toString(16)} bytes=${header.dataLength}",
            )
        } else {
            EMPTY_PAYLOAD
        }
        return AdbPacketDispatcher.ReadResult.PacketReady(
            AdbPacketDispatcher.Packet(
                command = header.command,
                arg0 = header.arg0,
                arg1 = header.arg1,
                checksum = header.checksum,
                magic = header.magic,
                payload = payload,
            ),
        )
    }

    private fun readHeader(timeoutMs: Int = AUTH_SIGNATURE_TIMEOUT_MS): Header? = readHeaderDirect(timeoutMs)

    private fun readHeaderDirect(timeoutMs: Int): Header? {
        val usbConnection = connection ?: return null
        val endpoint = endpointIn ?: return null
        val buffer = inboundHeaderBuffer
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = usbConnection.bulkTransfer(
                endpoint,
                buffer,
                totalRead,
                buffer.size - totalRead,
                timeoutMs,
            )
            if (read <= 0) {
                if (totalRead > 0) {
                    directReadFailureCode = AdbPacketDispatcher.FailureCode.PARTIAL_HEADER_TIMEOUT
                    directReadFailureMessage =
                        "ADB header interrupted after $totalRead/${buffer.size} bytes (result=$read)"
                }
                return null
            }
            totalRead += read
        }

        val command = readIntLe(buffer, 0).toLong() and 0xFFFF_FFFFL
        val arg0 = readIntLe(buffer, 4)
        val arg1 = readIntLe(buffer, 8)
        val dataLength = readIntLe(buffer, 12)
        val checksum = readIntLe(buffer, 16)
        val magic = readIntLe(buffer, 20)

        if (magic != command.inv().toInt()) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_HEADER
            directReadFailureMessage = "ADB header magic mismatch cmd=0x${command.toString(16)}"
            return null
        }
        if (dataLength !in 0..MAX_PAYLOAD) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_HEADER
            directReadFailureMessage = "ADB header payload length out of range: $dataLength"
            return null
        }

        if (dataLength == 0) {
            if (!AdbChecksum.matches(checksum, EMPTY_PAYLOAD, LOCAL_ADB_VERSION, peerProtocolVersion)) {
                directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
                directReadFailureMessage = "ADB empty-payload checksum mismatch cmd=0x${command.toString(16)}"
                return null
            }
            clearPendingInboundPayload()
        } else {
            pendingInboundChecksum = checksum
            pendingInboundLength = dataLength
            pendingInboundCommand = command
        }
        return Header(command, arg0, arg1, dataLength, checksum, magic)
    }

    private fun readData(length: Int): ByteArray? = readDataDirect(length)

    private fun readDataDirect(length: Int): ByteArray? {
        val usbConnection = connection ?: return null
        val endpoint = endpointIn ?: return null
        if (length !in 0..MAX_PAYLOAD) return null

        val expectedChecksum = pendingInboundChecksum
        val expectedLength = pendingInboundLength
        val command = pendingInboundCommand
        clearPendingInboundPayload()

        if (length != expectedLength) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.INVALID_PAYLOAD
            directReadFailureMessage = "ADB payload length mismatch header=$expectedLength requested=$length"
            return null
        }
        if (length == 0) return EMPTY_PAYLOAD

        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val chunkLength = minOf(USB_BULK_CHUNK_BYTES, length - totalRead)
            val read = usbConnection.bulkTransfer(
                endpoint,
                buffer,
                totalRead,
                chunkLength,
                USB_READ_TIMEOUT_MS,
            )
            if (read <= 0) {
                directReadFailureCode = if (connection == null || closed) {
                    AdbPacketDispatcher.FailureCode.DEVICE_DISCONNECTED
                } else {
                    AdbPacketDispatcher.FailureCode.USB_IN_FAILED
                }
                directReadFailureMessage = "ADB payload USB IN failed after $totalRead/$length bytes result=$read"
                return null
            }
            totalRead += read
        }

        if (expectedChecksum != null && !AdbChecksum.matches(
                expectedChecksum,
                buffer,
                LOCAL_ADB_VERSION,
                peerProtocolVersion,
            )
        ) {
            directReadFailureCode = AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH
            directReadFailureMessage =
                "ADB payload checksum mismatch cmd=0x${command.toString(16)} bytes=$length"
            return null
        }
        return buffer
    }

    private fun consumeUnexpectedPayload(header: Header) {
        if (header.dataLength > 0) readData(header.dataLength)
    }

    private fun failConnect(message: String): ConnectionInfo? {
        event("ADB_CONNECT_FAILED", message)
        close()
        return null
    }

    private fun clearPendingInboundPayload() {
        pendingInboundChecksum = null
        pendingInboundLength = 0
        pendingInboundCommand = 0L
    }

    private fun event(name: String, detail: String) {
        onEvent(name, detail.take(1_000))
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val A_CNXN = 0x4E584E43L
        private const val A_AUTH = 0x48545541L
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val ANDROID_USB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
        private const val ADB_HEADER_SIZE = 24
        private const val MAX_PAYLOAD = 1_048_576
        private const val USB_BULK_CHUNK_BYTES = 16 * 1024
        private const val USB_WRITE_TIMEOUT_MS = 5_000
        private const val USB_READ_TIMEOUT_MS = 5_000
        private const val AUTH_SIGNATURE_TIMEOUT_MS = 10_000
        private const val AUTH_PUBLIC_KEY_TIMEOUT_MS = 60_000
        private const val AUTH_RESPONSE_LIMIT = 12
        private const val READ_ONLY_PROBE_PACKET_TIMEOUT_MS = 10_000
        private const val READ_ONLY_PROBE_TOTAL_TIMEOUT_MS = 30_000L
        private const val READ_ONLY_PROBE_VALUE_LIMIT = 200
        private const val LOCAL_ADB_VERSION = AdbChecksum.VERSION_WITH_CHECKSUM
        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
