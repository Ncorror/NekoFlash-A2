package io.github.ncorror.nekoflash.fastboot.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootValueParserTest {
    @Test
    fun `boolean parser accepts legacy true tokens`() {
        assertTrue(FastbootValueParser.parseBoolean(" YES ") == true)
        assertTrue(FastbootValueParser.parseBoolean("True") == true)
        assertTrue(FastbootValueParser.parseBoolean("1") == true)
    }

    @Test
    fun `boolean parser accepts legacy false tokens`() {
        assertFalse(FastbootValueParser.parseBoolean(" NO ") ?: true)
        assertFalse(FastbootValueParser.parseBoolean("False") ?: true)
        assertFalse(FastbootValueParser.parseBoolean("0") ?: true)
    }

    @Test
    fun `boolean parser leaves unknown values unknown`() {
        assertNull(FastbootValueParser.parseBoolean(null))
        assertNull(FastbootValueParser.parseBoolean("on"))
        assertNull(FastbootValueParser.parseBoolean(""))
    }

    @Test
    fun `snapshot parser preserves legacy token mapping`() {
        assertEquals(
            FastbootValueParser.SnapshotState.NONE,
            FastbootValueParser.parseSnapshotState("cancelled"),
        )
        assertEquals(
            FastbootValueParser.SnapshotState.NONE,
            FastbootValueParser.parseSnapshotState(" NONE "),
        )
        assertEquals(
            FastbootValueParser.SnapshotState.SNAPSHOTTED,
            FastbootValueParser.parseSnapshotState("snapshotted"),
        )
        assertEquals(
            FastbootValueParser.SnapshotState.MERGING,
            FastbootValueParser.parseSnapshotState("MERGING"),
        )
        assertEquals(
            FastbootValueParser.SnapshotState.UNKNOWN,
            FastbootValueParser.parseSnapshotState(null),
        )
    }
}
