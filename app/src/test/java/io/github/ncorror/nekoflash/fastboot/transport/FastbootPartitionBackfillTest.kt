package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootPartitionBackfillTest {
    @Test
    fun pointProbeFillsMissingMetadataAndStorage() {
        val source = FastbootGetVarAllPlan.parse(
            listOf("product: vayu", "partition-size:boot: 0x4000000"),
        )
        val probe = FastbootPartitionInventory.PointProbe(
            name = "boot",
            type = "raw",
            logical = false,
            attemptedFields = setOf(
                FastbootGetVarAllPlan.MetadataField.TYPE,
                FastbootGetVarAllPlan.MetadataField.LOGICAL,
            ),
            resolvedFields = setOf(
                FastbootGetVarAllPlan.MetadataField.TYPE,
                FastbootGetVarAllPlan.MetadataField.LOGICAL,
            ),
        )

        val inventory = FastbootPartitionInventory.from(source, pointProbes = listOf(probe))
        val boot = inventory.partition("boot")!!

        assertEquals("raw", boot.type)
        assertEquals(false, boot.logical)
        assertEquals(FastbootPartitionInventory.StorageKind.PHYSICAL, boot.storage)
        assertTrue(boot.missingFields.isEmpty())
        assertEquals(2, inventory.pointQueryCount)
        assertEquals(0, inventory.unresolvedPointQueryCount)
    }

    @Test
    fun hasSlotOnlyPointProbeNeverInventsPartition() {
        val source = FastbootGetVarAllPlan.parse(listOf("product: other"))
        val probe = FastbootPartitionInventory.PointProbe(
            name = "boot",
            hasSlot = true,
            attemptedFields = setOf(FastbootGetVarAllPlan.MetadataField.HAS_SLOT),
            resolvedFields = setOf(FastbootGetVarAllPlan.MetadataField.HAS_SLOT),
        )

        val inventory = FastbootPartitionInventory.from(source, pointProbes = listOf(probe))

        assertTrue(inventory.entries.isEmpty())
        assertEquals(true, inventory.slotFamilies["boot"])
    }

    @Test
    fun unresolvedPointQueryIsCountedWithoutInventingMetadata() {
        val source = FastbootGetVarAllPlan.parse(
            listOf("product: vayu", "partition-size:boot: 0x4000000"),
        )
        val probe = FastbootPartitionInventory.PointProbe(
            name = "boot",
            attemptedFields = setOf(FastbootGetVarAllPlan.MetadataField.TYPE),
        )

        val inventory = FastbootPartitionInventory.from(source, pointProbes = listOf(probe))

        assertEquals(1, inventory.pointQueryCount)
        assertEquals(1, inventory.unresolvedPointQueryCount)
        assertTrue(FastbootGetVarAllPlan.MetadataField.TYPE in inventory.partition("boot")!!.missingFields)
    }

    @Test
    fun conflictingPointMetadataAddsPrivacySafeWarningAndUsesPointValue() {
        val source = FastbootGetVarAllPlan.parse(
            listOf("product: vayu", "partition-type:boot: raw"),
        )
        val probe = FastbootPartitionInventory.PointProbe(
            name = "boot",
            type = "ext4",
            attemptedFields = setOf(FastbootGetVarAllPlan.MetadataField.TYPE),
            resolvedFields = setOf(FastbootGetVarAllPlan.MetadataField.TYPE),
        )

        val inventory = FastbootPartitionInventory.from(source, pointProbes = listOf(probe))

        assertEquals("ext4", inventory.partition("boot")!!.type)
        assertTrue(inventory.warnings.any { it.code == "PARTITION_METADATA_CONFLICT" })
        assertFalse(inventory.warnings.toString().contains("raw"))
        assertFalse(inventory.warnings.toString().contains("ext4"))
    }

    @Test
    fun colonScopedGetvarInfoPrefixNormalizesCorrectly() {
        val value = FastbootReadOnlySession.normalizeGetVarValue(
            "partition-size:boot",
            "partition-size:boot: 0x4000000",
        )

        assertEquals("0x4000000", value)
    }
}
