package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootReadOnlySessionTest {
    @Test
    fun `product command is fixed and read only`() {
        assertEquals("getvar:product", FastbootReadOnlySession.PRODUCT_COMMAND)
    }

    @Test
    fun `direct OKAY payload qualifies peer and returns product`() {
        val session = FastbootReadOnlySession()

        val decision = session.accept(packet("OKAYvayu"))

        assertEquals(
            FastbootReadOnlySession.Decision.Qualified(
                product = "vayu",
                finalType = "OKAY",
                finalPayload = "vayu",
            ),
            decision,
        )
    }

    @Test
    fun `INFO value followed by empty OKAY returns the latest product`() {
        val session = FastbootReadOnlySession()

        assertEquals(FastbootReadOnlySession.Decision.Continue, session.accept(packet("INFOproduct: vayu")))
        val decision = session.accept(packet("OKAY"))

        assertEquals(
            FastbootReadOnlySession.Decision.Qualified(
                product = "vayu",
                finalType = "OKAY",
                finalPayload = "",
            ),
            decision,
        )
    }

    @Test
    fun `legacy underscore variant is normalized`() {
        assertEquals(
            "1234",
            FastbootReadOnlySession.normalizeGetVarValue("max-download-size", "max_download_size: 1234"),
        )
    }

    @Test
    fun `protocol FAIL still qualifies fastboot peer without product`() {
        val session = FastbootReadOnlySession()

        val decision = session.accept(packet("FAILVariable not found"))

        assertTrue(decision is FastbootReadOnlySession.Decision.Qualified)
        decision as FastbootReadOnlySession.Decision.Qualified
        assertNull(decision.product)
        assertEquals("FAIL", decision.finalType)
        assertEquals("Variable not found", decision.finalPayload)
    }

    @Test
    fun `unknown and short packets do not qualify peer`() {
        val session = FastbootReadOnlySession()

        assertEquals(FastbootReadOnlySession.Decision.Continue, session.accept(packet("WTF?payload")))
        assertEquals(FastbootReadOnlySession.Decision.Continue, session.accept(packet("OK")))
    }

    @Test
    fun `NUL bytes are removed before fastboot packet parsing`() {
        val parsed = FastbootReadOnlySession.parsePacket("OKAYvayu\u0000\u0000".toByteArray(Charsets.US_ASCII))

        assertEquals("OKAY", parsed.type)
        assertEquals("vayu", parsed.payload)
        assertEquals("OKAYvayu", parsed.raw)
    }

    @Test
    fun `legacy Fastboot timing constants stay pinned`() {
        assertEquals(350L, FastbootReadOnlyTiming.HANDSHAKE_SETTLE_MS)
        assertEquals(7_000, FastbootReadOnlyTiming.HANDSHAKE_TIMEOUT_MS)
        assertEquals(900, FastbootReadOnlyTiming.READ_SLICE_MS)
        assertEquals(3, FastbootReadOnlyTiming.MAX_FAILED_READS)
        assertEquals(100L, FastbootReadOnlyTiming.READ_RETRY_DELAY_MS)
        assertEquals(1_500L, FastbootReadOnlyTiming.MIN_PATIENCE_MS)
    }

    @Test
    fun `three empty reads do not fail before minimum patience`() {
        assertEquals(false, FastbootReadOnlyTiming.shouldFailAfterEmptyRead(3, 1_499L))
        assertEquals(true, FastbootReadOnlyTiming.shouldFailAfterEmptyRead(3, 1_500L))
    }

    @Test
    fun `read slice is bounded by remaining handshake budget`() {
        assertEquals(900, FastbootReadOnlyTiming.nextReadTimeoutMs(7_000L))
        assertEquals(125, FastbootReadOnlyTiming.nextReadTimeoutMs(125L))
        assertEquals(1, FastbootReadOnlyTiming.nextReadTimeoutMs(0L))
    }

    private fun packet(raw: String): FastbootReadOnlySession.Packet =
        FastbootReadOnlySession.parsePacket(raw.toByteArray(Charsets.US_ASCII))
}
