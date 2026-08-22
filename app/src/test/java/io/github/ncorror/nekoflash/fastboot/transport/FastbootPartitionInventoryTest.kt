package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootPartitionInventoryTest {
    @Test
    fun vayuRemainsLegacyAOnlyDespiteNoisySlotMetadata() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: vayu",
                "current-slot: b",
                "slot-count: 2",
                "partition-size:boot_a: 0x4000000",
                "has-slot:boot: yes",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertEquals(FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY, inventory.topology)
        assertEquals(FastbootPartitionInventory.SlotBinding.UNSLOTTED, inventory.entries.single().slotBinding)
        assertTrue(inventory.warnings.any { it.code == "LEGACY_SLOT_CONTRADICTION" })
    }

    @Test
    fun hasSlotFamilyAloneNeverInventsPartition() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: generic",
                "has-slot:boot: yes",
                "has-slot:recovery: no",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertTrue(inventory.entries.isEmpty())
        assertEquals(2, inventory.slotFamilies.size)
        assertTrue(inventory.warnings.any { it.code == "NO_CONCRETE_PARTITIONS" })
    }

    @Test
    fun concreteMetadataBuildsOnlyEvidenceBackedEntries() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: generic",
                "partition-size:boot: 0x4000000",
                "partition-type:boot: raw",
                "has-slot:boot: no",
                "is-logical:system: yes",
                "has-slot:vendor: yes",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertEquals(setOf("boot", "system"), inventory.entries.map { it.name }.toSet())
        val boot = inventory.entries.first { it.name == "boot" }
        assertEquals(0x4000000L, boot.sizeBytes)
        assertEquals("raw", boot.type)
        assertEquals(false, boot.hasSlot)
        assertEquals(FastbootPartitionInventory.RiskTier.NORMAL, boot.risk)
        val system = inventory.entries.first { it.name == "system" }
        assertEquals(FastbootPartitionInventory.StorageKind.LOGICAL, system.storage)
        assertEquals(FastbootPartitionInventory.RiskTier.CRITICAL, system.risk)
    }

    @Test
    fun nonVayuSlottedConcreteNamesConfirmAbTopology() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: other",
                "partition-size:boot_a: 0x4000000",
                "partition-size:boot_b: 0x4000000",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertEquals(FastbootPartitionInventory.SlotTopology.A_B, inventory.topology)
        assertNull(inventory.currentSlot)
        assertTrue(inventory.warnings.any { it.code == "CURRENT_SLOT_UNKNOWN" })
        assertEquals(FastbootPartitionInventory.SlotBinding.SLOT_A, inventory.entries.first { it.name == "boot_a" }.slotBinding)
        assertEquals(FastbootPartitionInventory.SlotBinding.SLOT_B, inventory.entries.first { it.name == "boot_b" }.slotBinding)
    }

    @Test
    fun missingSlotEvidenceStaysUnknownForUnrecognizedProduct() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: other",
                "partition-size:misc: 0x100000",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertEquals(FastbootPartitionInventory.SlotTopology.UNKNOWN, inventory.topology)
        assertTrue(inventory.warnings.any { it.code == "SLOT_TOPOLOGY_UNKNOWN" })
    }

    @Test
    fun supplementalSlotFactsAreUsedWithoutOverridingGetvarAll() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: other",
                "partition-size:boot: 0x4000000",
            ),
        )

        val inventory = FastbootPartitionInventory.from(
            source = source,
            supplementalVariables = mapOf("current-slot" to "a", "slot-count" to "2"),
        )

        assertEquals(FastbootPartitionInventory.SlotTopology.A_B, inventory.topology)
        assertEquals("a", inventory.currentSlot)
    }

    @Test
    fun duplicateWarningsNeverCarryRawSerialValues() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: vayu",
                "serialno: SECRET-ONE",
                "serialno: SECRET-TWO",
                "partition-size:boot: 0x4000000",
            ),
        )

        val inventory = FastbootPartitionInventory.from(source)
        val rendered = inventory.toString()

        assertTrue(inventory.warnings.any { it.code == "CONFLICTING_DUPLICATE" })
        assertFalse(rendered.contains("SECRET-ONE"))
        assertFalse(rendered.contains("SECRET-TWO"))
    }

    @Test
    fun summaryContainsOnlySafeAggregates() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: vayu",
                "serialno: SECRET-DEVICE-ID",
                "partition-size:boot: 0x4000000",
                "partition-type:vbmeta: raw",
                "is-logical:system: yes",
            ),
        )

        val summary = FastbootPartitionInventory.from(source).summary()

        assertTrue(summary.productReported)
        assertEquals(FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY, summary.topology)
        assertEquals(3, summary.entryCount)
        assertFalse(summary.toString().contains("SECRET-DEVICE-ID"))
    }

    @Test
    fun partialSnapshotRemainsInventoryButIsMarkedIncomplete() {
        val source = FastbootGetVarAllPlan.parse(
            lines = listOf("product: vayu", "partition-size:boot: 0x4000000"),
            complete = false,
            finalStatus = "FAIL",
            finalMessage = "partial",
        )

        val inventory = FastbootPartitionInventory.from(source)

        assertFalse(inventory.complete)
        assertEquals(1, inventory.entries.size)
        assertTrue(inventory.warnings.any { it.code == "PARTIAL_GETVAR_ALL" })
        assertTrue(inventory.warnings.any { it.code == "GETVAR_ALL_FINAL_STATUS" })
    }
}
