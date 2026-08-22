package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootGetVarAllSnapshotTest {
    @Test
    fun commandAndBudgetsStayPinnedToLegacy() {
        assertEquals("getvar:all", FastbootGetVarAllPlan.COMMAND)
        assertEquals(10_000, FastbootGetVarAllPlan.COMMAND_TIMEOUT_MS)
        assertEquals(30_000, FastbootGetVarAllPlan.RESPONSE_TIMEOUT_MS)
        assertEquals(8, FastbootGetVarAllPlan.MAX_FAILED_READS)
    }

    @Test
    fun parserAcceptsInfoTextAndBootloaderPrefixes() {
        val snapshot = FastbootGetVarAllPlan.parse(
            listOf(
                "INFOproduct: vayu",
                "TEXTsecure: yes",
                "(bootloader) anti: 2",
                "all: done!",
            ),
        )

        assertEquals("vayu", snapshot.variables["product"])
        assertEquals("yes", snapshot.variables["secure"])
        assertEquals("2", snapshot.variables["anti"])
        assertEquals(3, snapshot.variables.size)
    }

    @Test
    fun parserBuildsOnlyReportedPartitionMetadata() {
        val snapshot = FastbootGetVarAllPlan.parse(
            listOf(
                "partition-size:boot: 0x4000000",
                "partition-type:boot: raw",
                "has-slot:boot: no",
                "is-logical:system: yes",
            ),
        )

        val boot = snapshot.partitions.first { it.name == "boot" }
        assertEquals(0x4000000L, boot.sizeBytes)
        assertEquals("raw", boot.type)
        assertEquals(false, boot.hasSlot)
        val system = snapshot.partitions.first { it.name == "system" }
        assertEquals(true, system.logical)
        assertNull(system.sizeBytes)
    }

    @Test
    fun duplicatesUseLastValueAndExposeConflictCount() {
        val snapshot = FastbootGetVarAllPlan.parse(
            listOf("secure: no", "secure: yes", "product: vayu", "product: vayu"),
        )
        val summary = snapshot.summary()

        assertEquals("yes", snapshot.variables["secure"])
        assertEquals(2, summary.duplicateVariableCount)
        assertEquals(1, summary.conflictingDuplicateCount)
    }

    @Test
    fun summaryNeverCarriesRawSerialValue() {
        val snapshot = FastbootGetVarAllPlan.parse(
            listOf("serialno: SECRET-DEVICE-ID", "product: vayu"),
        )
        val summary = snapshot.summary()

        assertTrue(summary.serialReported)
        assertFalse(summary.toString().contains("SECRET-DEVICE-ID"))
    }

    @Test
    fun partialFailRemainsUsableSnapshot() {
        val snapshot = FastbootGetVarAllPlan.parse(
            listOf("product: vayu"),
            complete = false,
            finalStatus = "FAIL",
            finalMessage = "not complete",
        )
        val summary = snapshot.summary()

        assertTrue(summary.supported)
        assertFalse(summary.complete)
        assertEquals("FAIL", summary.finalStatus)
        assertEquals(1, summary.variableCount)
    }

    @Test
    fun unsupportedFailHasNoInventedValues() {
        val summary = FastbootGetVarAllPlan.unsupported()

        assertFalse(summary.supported)
        assertFalse(summary.complete)
        assertEquals("FAIL", summary.finalStatus)
        assertEquals(0, summary.variableCount)
        assertFalse(summary.serialReported)
    }

    @Test
    fun sizeParserMatchesLegacyHexAndDecimalRules() {
        assertEquals(805306368L, FastbootGetVarAllPlan.parseSizeValue("805306368"))
        assertEquals(0x30000000L, FastbootGetVarAllPlan.parseSizeValue("0x30000000"))
        assertNull(FastbootGetVarAllPlan.parseSizeValue("-1"))
        assertNull(FastbootGetVarAllPlan.parseSizeValue("not-a-size"))
    }
}
