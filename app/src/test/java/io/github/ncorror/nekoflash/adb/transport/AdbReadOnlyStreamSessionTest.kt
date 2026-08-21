package io.github.ncorror.nekoflash.adb.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReadOnlyStreamSessionTest {
    @Test
    fun `open request is fixed read only service and nul terminated`() {
        val session = AdbReadOnlyStreamSession(localId = 1)

        val request = session.openRequest()

        assertEquals(AdbReadOnlyStreamSession.A_OPEN, request.command)
        assertEquals(1, request.arg0)
        assertEquals(0, request.arg1)
        assertArrayEquals(
            "${AdbReadOnlyStreamSession.READ_ONLY_SERVICE}\u0000".toByteArray(),
            request.payload,
        )
    }

    @Test
    fun `opened stream acknowledges writes and returns collected output on close`() {
        val session = AdbReadOnlyStreamSession(localId = 7)
        session.openRequest()

        val opened = session.consume(packet(AdbReadOnlyStreamSession.A_OKAY, arg0 = 42, arg1 = 7))
        val data = session.consume(
            packet(
                AdbReadOnlyStreamSession.A_WRTE,
                arg0 = 42,
                arg1 = 7,
                payload = "vayu\n".toByteArray(),
            ),
        )
        val closed = session.consume(packet(AdbReadOnlyStreamSession.A_CLSE, arg0 = 42, arg1 = 7))

        assertEquals(AdbReadOnlyStreamSession.Transition.OPENED, opened.transition)
        assertEquals(AdbReadOnlyStreamSession.Transition.DATA, data.transition)
        assertEquals(AdbReadOnlyStreamSession.A_OKAY, data.outbound.single().command)
        assertEquals(AdbReadOnlyStreamSession.Transition.COMPLETED, closed.transition)
        assertEquals(AdbReadOnlyStreamSession.A_CLSE, closed.outbound.single().command)
        assertEquals("vayu\n", session.outputText())
    }

    @Test
    fun `early write matches legacy by acking but not returning payload`() {
        val session = AdbReadOnlyStreamSession(localId = 3)
        session.openRequest()

        val early = session.consume(
            packet(
                AdbReadOnlyStreamSession.A_WRTE,
                arg0 = 9,
                arg1 = 3,
                payload = "ignored".toByteArray(),
            ),
        )
        session.consume(packet(AdbReadOnlyStreamSession.A_OKAY, arg0 = 9, arg1 = 3))
        session.consume(packet(AdbReadOnlyStreamSession.A_CLSE, arg0 = 9, arg1 = 3))

        assertEquals(AdbReadOnlyStreamSession.Transition.EARLY_DATA_IGNORED, early.transition)
        assertEquals(AdbReadOnlyStreamSession.A_OKAY, early.outbound.single().command)
        assertEquals("", session.outputText())
    }

    @Test
    fun `stale write is closed instead of contaminating current stream`() {
        val session = AdbReadOnlyStreamSession(localId = 5)
        session.openRequest()
        session.consume(packet(AdbReadOnlyStreamSession.A_OKAY, arg0 = 20, arg1 = 5))

        val stale = session.consume(
            packet(
                AdbReadOnlyStreamSession.A_WRTE,
                arg0 = 99,
                arg1 = 4,
                payload = "stale".toByteArray(),
            ),
        )

        assertEquals(AdbReadOnlyStreamSession.Transition.STALE_PACKET, stale.transition)
        assertEquals(1, stale.outbound.size)
        assertEquals(AdbReadOnlyStreamSession.A_CLSE, stale.outbound.single().command)
        assertEquals(4, stale.outbound.single().arg0)
        assertEquals(99, stale.outbound.single().arg1)
        assertEquals("", session.outputText())
    }

    @Test
    fun `stale close is acknowledged without closing current stream`() {
        val session = AdbReadOnlyStreamSession(localId = 6)
        session.openRequest()
        session.consume(packet(AdbReadOnlyStreamSession.A_OKAY, arg0 = 30, arg1 = 6))

        val stale = session.consume(
            packet(AdbReadOnlyStreamSession.A_CLSE, arg0 = 77, arg1 = 5),
        )
        val data = session.consume(
            packet(
                AdbReadOnlyStreamSession.A_WRTE,
                arg0 = 30,
                arg1 = 6,
                payload = "vayu\n".toByteArray(),
            ),
        )

        assertEquals(AdbReadOnlyStreamSession.Transition.STALE_PACKET, stale.transition)
        assertEquals(AdbReadOnlyStreamSession.A_CLSE, stale.outbound.single().command)
        assertEquals(5, stale.outbound.single().arg0)
        assertEquals(77, stale.outbound.single().arg1)
        assertEquals(AdbReadOnlyStreamSession.Transition.DATA, data.transition)
        assertEquals("vayu\n", session.outputText())
    }


    @Test
    fun `bounded probe fails closed when output cap is exceeded`() {
        val session = AdbReadOnlyStreamSession(localId = 2, maxOutputBytes = 4)
        session.openRequest()
        session.consume(packet(AdbReadOnlyStreamSession.A_OKAY, arg0 = 8, arg1 = 2))

        val result = session.consume(
            packet(
                AdbReadOnlyStreamSession.A_WRTE,
                arg0 = 8,
                arg1 = 2,
                payload = byteArrayOf(1, 2, 3, 4, 5),
            ),
        )

        assertEquals(AdbReadOnlyStreamSession.Transition.FAILED, result.transition)
        assertEquals(
            listOf(AdbReadOnlyStreamSession.A_OKAY, AdbReadOnlyStreamSession.A_CLSE),
            result.outbound.map { it.command },
        )
        assertTrue(result.detail.contains("output cap exceeded"))
    }

    private fun packet(
        command: Long,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = ByteArray(0),
    ) = AdbPacketDispatcher.Packet(
        command = command,
        arg0 = arg0,
        arg1 = arg1,
        checksum = 0,
        magic = command.inv().toInt(),
        payload = payload,
    )
}
