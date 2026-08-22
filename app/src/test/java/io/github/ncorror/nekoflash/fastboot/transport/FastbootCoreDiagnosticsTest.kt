package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootCoreDiagnosticsTest {
    @Test
    fun `core diagnostic command surface is fixed and read only`() {
        assertEquals(
            listOf(
                "getvar:current-slot",
                "getvar:slot-count",
                "getvar:unlocked",
                "getvar:max-download-size",
            ),
            FastbootCoreDiagnosticsPlan.variables.map { it.command },
        )
    }

    @Test
    fun `core diagnostic getvars keep legacy point query timeout`() {
        assertEquals(5_000, FastbootCoreDiagnosticsPlan.GETVAR_TIMEOUT_MS)
        assertTrue(FastbootCoreDiagnosticsPlan.variables.all { it.timeoutMs == 5_000 })
    }

    @Test
    fun `direct OKAY yields normalized point diagnostic value`() {
        val session = FastbootReadOnlyGetVarSession("current-slot")

        val decision = session.accept(packet("OKAYcurrent-slot: a"))

        assertEquals(
            FastbootReadOnlyGetVarSession.Decision.Complete(
                value = "a",
                finalType = "OKAY",
                finalPayload = "current-slot: a",
            ),
            decision,
        )
    }

    @Test
    fun `INFO value followed by empty OKAY yields latest diagnostic value`() {
        val session = FastbootReadOnlyGetVarSession("unlocked")

        assertEquals(
            FastbootReadOnlyGetVarSession.Decision.Continue,
            session.accept(packet("INFOunlocked: yes")),
        )
        assertEquals(
            FastbootReadOnlyGetVarSession.Decision.Complete(
                value = "yes",
                finalType = "OKAY",
                finalPayload = "",
            ),
            session.accept(packet("OKAY")),
        )
    }

    @Test
    fun `protocol FAIL leaves optional diagnostic unreported without inventing value`() {
        val session = FastbootReadOnlyGetVarSession("slot-count")

        val decision = session.accept(packet("FAILVariable not found"))

        assertEquals(
            FastbootReadOnlyGetVarSession.Decision.Complete(
                value = null,
                finalType = "FAIL",
                finalPayload = "Variable not found",
            ),
            decision,
        )
    }

    @Test
    fun `unknown packet does not complete diagnostic query`() {
        val session = FastbootReadOnlyGetVarSession("unlocked")

        assertEquals(
            FastbootReadOnlyGetVarSession.Decision.Continue,
            session.accept(packet("WTF?payload")),
        )
    }

    @Test
    fun `max download size accepts legacy hexadecimal representation`() {
        assertEquals(0x10000000L, FastbootCoreDiagnosticsPlan.parseFastbootSize("0x10000000"))
    }

    @Test
    fun `max download size accepts decimal token and rejects absent token`() {
        assertEquals(268_435_456L, FastbootCoreDiagnosticsPlan.parseFastbootSize("268435456 bytes"))
        assertNull(FastbootCoreDiagnosticsPlan.parseFastbootSize("unknown"))
    }

    private fun packet(raw: String): FastbootReadOnlySession.Packet =
        FastbootReadOnlySession.parsePacket(raw.toByteArray(Charsets.US_ASCII))
}
