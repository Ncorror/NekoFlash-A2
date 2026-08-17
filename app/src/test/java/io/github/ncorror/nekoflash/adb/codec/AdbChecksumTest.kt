package io.github.ncorror.nekoflash.adb.codec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbChecksumTest {
    @Test
    fun `checksum sums bytes as unsigned values`() {
        val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())

        assertEquals(511, AdbChecksum.compute(payload))
    }

    @Test
    fun `checksum is required when either negotiated side uses classic version`() {
        assertTrue(
            AdbChecksum.isRequired(
                localVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
            ),
        )
        assertTrue(
            AdbChecksum.isRequired(
                localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
            ),
        )
    }

    @Test
    fun `checksum may be skipped only when both sides support skip version`() {
        assertFalse(
            AdbChecksum.isRequired(
                localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
            ),
        )
    }

    @Test
    fun `classic session rejects mismatched checksum`() {
        val payload = byteArrayOf(1, 2, 3)

        assertFalse(
            AdbChecksum.matches(
                expected = 5,
                payload = payload,
                localVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
            ),
        )
        assertTrue(
            AdbChecksum.matches(
                expected = 6,
                payload = payload,
                localVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_WITH_CHECKSUM,
            ),
        )
    }

    @Test
    fun `skip-checksum session accepts data-check value without recomputing`() {
        assertTrue(
            AdbChecksum.matches(
                expected = 0,
                payload = byteArrayOf(1, 2, 3),
                localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
                peerVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
            ),
        )
    }
}
