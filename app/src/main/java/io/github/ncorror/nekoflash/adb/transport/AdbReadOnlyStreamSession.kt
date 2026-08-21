package io.github.ncorror.nekoflash.adb.transport

import java.io.ByteArrayOutputStream

/**
 * Pure state machine for the first Stage 6B read-only ADB service probe.
 *
 * The public surface is deliberately narrow: callers can open only the fixed
 * `shell:getprop ro.product.device` service. This preserves the legacy ADB stream
 * framing while avoiding a generic command API before read-only hardware parity
 * is proven.
 */
internal class AdbReadOnlyStreamSession(
    val localId: Int,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    data class OutboundPacket(
        val command: Long,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    enum class Transition {
        OPENED,
        DATA,
        EARLY_DATA_IGNORED,
        COMPLETED,
        FAILED,
        STALE_PACKET,
        UNEXPECTED_PACKET,
    }

    data class Step(
        val transition: Transition,
        val outbound: List<OutboundPacket> = emptyList(),
        val detail: String = "",
    )

    private val output = ByteArrayOutputStream()
    private var opened = false
    private var terminal = false
    private var remoteId = 0

    init {
        require(localId > 0) { "ADB local stream id must be positive" }
        require(maxOutputBytes > 0) { "ADB read-only output cap must be positive" }
    }

    fun openRequest(): OutboundPacket {
        check(!opened && !terminal) { "ADB read-only stream already started" }
        return OutboundPacket(
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = "$READ_ONLY_SERVICE\u0000".toByteArray(Charsets.UTF_8),
        )
    }

    fun consume(packet: AdbPacketDispatcher.Packet): Step {
        if (terminal) {
            return Step(Transition.UNEXPECTED_PACKET, detail = "packet received after terminal state")
        }

        return when (packet.command) {
            A_OKAY -> consumeOkay(packet)
            A_WRTE -> consumeWrite(packet)
            A_CLSE -> consumeClose(packet)
            else -> Step(
                Transition.UNEXPECTED_PACKET,
                detail = "cmd=0x${packet.command.toString(16)}",
            )
        }
    }

    fun timeoutClosePacket(): OutboundPacket? {
        if (terminal || remoteId <= 0) return null
        terminal = true
        return closePacket(localId, remoteId)
    }

    fun outputText(): String = output.toByteArray().toString(Charsets.UTF_8)

    private fun consumeOkay(packet: AdbPacketDispatcher.Packet): Step {
        if (!targetsLocal(packet)) {
            return Step(
                Transition.STALE_PACKET,
                detail = "stale OKAY local=${packet.arg1} expected=$localId",
            )
        }
        remoteId = packet.arg0
        opened = true
        return Step(Transition.OPENED, detail = "local=$localId remote=$remoteId")
    }

    private fun consumeWrite(packet: AdbPacketDispatcher.Packet): Step {
        if (!targetsLocal(packet)) {
            return Step(
                transition = Transition.STALE_PACKET,
                outbound = staleClose(packet),
                detail = "stale WRTE local=${packet.arg1} expected=$localId",
            )
        }

        remoteId = packet.arg0
        val ack = OutboundPacket(A_OKAY, localId, remoteId, EMPTY_PAYLOAD)
        if (!opened) {
            // Matches legacy openAdbStream(): early WRTE is ACKed but not carried into
            // the command result until the stream has received its OKAY.
            return Step(
                transition = Transition.EARLY_DATA_IGNORED,
                outbound = listOf(ack),
                detail = "bytes=${packet.payload.size}",
            )
        }

        if (output.size() + packet.payload.size > maxOutputBytes) {
            terminal = true
            val close = closePacket(localId, remoteId)
            return Step(
                transition = Transition.FAILED,
                outbound = listOf(ack, close),
                detail = "output cap exceeded maxBytes=$maxOutputBytes",
            )
        }

        output.write(packet.payload)
        return Step(
            transition = Transition.DATA,
            outbound = listOf(ack),
            detail = "bytes=${packet.payload.size} total=${output.size()}",
        )
    }

    private fun consumeClose(packet: AdbPacketDispatcher.Packet): Step {
        if (!targetsLocal(packet)) {
            return Step(
                transition = Transition.STALE_PACKET,
                outbound = staleClose(packet),
                detail = "stale CLSE local=${packet.arg1} expected=$localId",
            )
        }

        remoteId = packet.arg0
        terminal = true
        val closeAck = closePacket(localId, remoteId).takeIf { remoteId > 0 }
        return if (opened) {
            Step(
                transition = Transition.COMPLETED,
                outbound = listOfNotNull(closeAck),
                detail = "bytes=${output.size()}",
            )
        } else {
            Step(
                transition = Transition.FAILED,
                outbound = listOfNotNull(closeAck),
                detail = "stream closed before OKAY",
            )
        }
    }

    private fun targetsLocal(packet: AdbPacketDispatcher.Packet): Boolean = packet.arg1 == localId

    private fun staleClose(packet: AdbPacketDispatcher.Packet): List<OutboundPacket> =
        if (packet.arg1 > 0 && packet.arg0 > 0) {
            listOf(closePacket(packet.arg1, packet.arg0))
        } else {
            emptyList()
        }

    private fun closePacket(local: Int, remote: Int): OutboundPacket =
        OutboundPacket(A_CLSE, local, remote, EMPTY_PAYLOAD)

    companion object {
        const val READ_ONLY_SERVICE = "shell:getprop ro.product.device"
        const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024

        const val A_OPEN = 0x4E45504FL
        const val A_OKAY = 0x59414B4FL
        const val A_CLSE = 0x45534C43L
        const val A_WRTE = 0x45545257L

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
