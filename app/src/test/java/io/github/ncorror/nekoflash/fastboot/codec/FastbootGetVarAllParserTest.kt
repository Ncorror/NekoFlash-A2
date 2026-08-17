package io.github.ncorror.nekoflash.fastboot.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootGetVarAllParserTest {
    @Test
    fun `parser strips transport prefixes and normalizes variable names`() {
        val result = FastbootGetVarAllParser.parse(
            listOf(
                "INFO(bootloader) PRODUCT: onyx",
                "TEXTcurrent-slot: A",
                "\u0000INFO serialno: 1234\u0000",
            ),
        )

        assertEquals("onyx", result.value("product"))
        assertEquals("A", result.value("CURRENT-SLOT"))
        assertEquals("1234", result.value(" serialno "))
    }

    @Test
    fun `last duplicate value wins while conflicts remain auditable`() {
        val result = FastbootGetVarAllParser.parse(
            listOf(
                "INFOproduct: first",
                "INFOproduct: first",
                "INFOproduct: second",
            ),
        )

        assertEquals("second", result.value("product"))
        assertEquals(1, result.duplicateVariables.size)
        assertEquals(listOf("first", "first", "second"), result.duplicateVariables.single().values)
        assertTrue(result.duplicateVariables.single().conflicting)
    }

    @Test
    fun `case-only duplicate values are not treated as conflicting`() {
        val result = FastbootGetVarAllParser.parse(
            listOf("INFOunlocked: YES", "INFOunlocked: yes"),
        )

        assertFalse(result.duplicateVariables.single().conflicting)
    }

    @Test
    fun `partition metadata is parsed without inventing concrete partition evidence`() {
        val result = FastbootGetVarAllParser.parse(
            listOf(
                "INFOhas-slot:boot: yes",
                "INFOpartition-size:system_a: 0x1000",
                "INFOpartition-type:system_a: ext4",
                "INFOis-logical:system_a: yes",
            ),
        )

        val boot = result.partition("boot")!!
        assertEquals(true, boot.hasSlot)
        assertFalse(boot.hasConcreteEvidence)

        val systemA = result.partition("SYSTEM_A")!!
        assertEquals(4096L, systemA.sizeBytes)
        assertEquals("ext4", systemA.type)
        assertEquals(true, systemA.logical)
        assertTrue(systemA.hasConcreteEvidence)
    }

    @Test
    fun `all done marker is ignored and malformed lines remain visible`() {
        val result = FastbootGetVarAllParser.parse(
            listOf(
                "INFOall: done!",
                "INFOthis is not a variable",
                "INFOempty:   ",
            ),
        )

        assertNull(result.value("all"))
        assertEquals(
            listOf("this is not a variable", "empty:"),
            result.ignoredLines,
        )
    }

    @Test
    fun `size parser preserves legacy decimal hex and invalid handling`() {
        assertEquals(16L, FastbootGetVarAllParser.parseSize("0x10"))
        assertEquals(16L, FastbootGetVarAllParser.parseSize("16"))
        assertNull(FastbootGetVarAllParser.parseSize("-1"))
        assertNull(FastbootGetVarAllParser.parseSize("0xnope"))
        assertNull(FastbootGetVarAllParser.parseSize(null))
    }

    @Test
    fun `completion metadata is carried through without manufacturing a message`() {
        val result = FastbootGetVarAllParser.parse(
            lines = emptyList(),
            complete = false,
            finalStatus = "FAIL",
            finalMessage = "   ",
        )

        assertFalse(result.complete)
        assertEquals("FAIL", result.finalStatus)
        assertNull(result.finalMessage)
    }
}
