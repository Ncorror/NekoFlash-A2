package io.github.ncorror.nekoflash.fastboot.partition

import io.github.ncorror.nekoflash.fastboot.codec.FastbootGetVarAllParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootPartitionInventoryBuilderTest {
    @Test
    fun `family slot metadata never creates a concrete partition`() {
        val inventory = inventoryOf("INFOhas-slot:boot: yes")

        assertTrue(inventory.entries.isEmpty())
        assertEquals(true, inventory.slotFamilies["boot"])
        assertEquals(FastbootSlotTopology.A_B, inventory.topology)
        assertNull(inventory.partition("boot"))
    }

    @Test
    fun `vayu remains legacy a only even when slot metadata is contradictory`() {
        val inventory = inventoryOf(
            "INFOproduct: vayu",
            "INFOslot-count: 2",
            "INFOcurrent-slot: b",
            "INFOpartition-size:boot_b: 0x1000",
            "INFOpartition-type:boot_b: raw",
            "INFOis-logical:boot_b: no",
        )

        assertEquals(FastbootSlotTopology.LEGACY_A_ONLY, inventory.topology)
        assertEquals(FastbootSlotBinding.UNSLOTTED, inventory.partition("boot_b")!!.slotBinding)
        assertTrue(
            inventory.warnings.any {
                it.code == "LEGACY_SLOT_CONTRADICTION" &&
                    it.severity == FastbootWarningSeverity.CRITICAL
            },
        )
    }

    @Test
    fun `rodin style evidence produces a b inventory without synthesizing names`() {
        val inventory = inventoryOf(
            "INFOproduct: rodin",
            "INFOslot-count: 2",
            "INFOcurrent-slot: a",
            "INFOpartition-size:vendor_boot_a: 0x4000000",
            "INFOpartition-type:vendor_boot_a: raw",
            "INFOis-logical:vendor_boot_a: no",
            "INFOpartition-size:vendor_boot_b: 0x4000000",
            "INFOpartition-type:vendor_boot_b: raw",
            "INFOis-logical:vendor_boot_b: no",
        )

        assertEquals("rodin", inventory.product)
        assertEquals("a", inventory.currentSlot)
        assertEquals(FastbootSlotTopology.A_B, inventory.topology)
        assertEquals(listOf("vendor_boot_a", "vendor_boot_b"), inventory.entries.map { it.name })
        assertEquals(FastbootSlotBinding.SLOT_A, inventory.partition("vendor_boot_a")!!.slotBinding)
        assertEquals(FastbootSlotBinding.SLOT_B, inventory.partition("vendor_boot_b")!!.slotBinding)
    }

    @Test
    fun `missing slot evidence stays unknown`() {
        val inventory = inventoryOf(
            "INFOpartition-size:boot: 0x1000",
            "INFOpartition-type:boot: raw",
            "INFOis-logical:boot: no",
        )

        assertEquals(FastbootSlotTopology.UNKNOWN, inventory.topology)
        assertEquals(FastbootSlotBinding.UNKNOWN, inventory.partition("boot")!!.slotBinding)
        assertTrue(inventory.warnings.any { it.code == "SLOT_TOPOLOGY_UNKNOWN" })
    }

    @Test
    fun `two explicit legacy no slot families confirm a only topology`() {
        val inventory = inventoryOf(
            "INFOhas-slot:boot: no",
            "INFOhas-slot:recovery: no",
        )

        assertEquals(FastbootSlotTopology.LEGACY_A_ONLY, inventory.topology)
    }

    @Test
    fun `point query must resolve concrete metadata before it can add a partition`() {
        val source = FastbootGetVarAllParser.parse(emptyList())
        val hasSlotOnly = FastbootPartitionProbe(
            name = "boot",
            hasSlot = true,
            attemptedFields = setOf(FastbootGetVarAllParser.MetadataField.HAS_SLOT),
            resolvedFields = setOf(FastbootGetVarAllParser.MetadataField.HAS_SLOT),
        )

        val familyOnly = FastbootPartitionInventoryBuilder.build(source, pointProbes = listOf(hasSlotOnly))
        assertTrue(familyOnly.entries.isEmpty())

        val concrete = FastbootPartitionProbe(
            name = "boot",
            sizeBytes = 4096L,
            attemptedFields = setOf(
                FastbootGetVarAllParser.MetadataField.SIZE,
                FastbootGetVarAllParser.MetadataField.TYPE,
            ),
            resolvedFields = setOf(FastbootGetVarAllParser.MetadataField.SIZE),
        )
        val inventory = FastbootPartitionInventoryBuilder.build(source, pointProbes = listOf(concrete))

        assertEquals(4096L, inventory.partition("boot")!!.sizeBytes)
        assertEquals(setOf(FastbootPartitionEvidenceSource.POINT_QUERY), inventory.partition("boot")!!.evidenceSources)
        assertEquals(2, inventory.pointQueryCount)
        assertEquals(1, inventory.unresolvedPointQueryCount)
    }

    @Test
    fun `newer point metadata wins conflicts while warning remains auditable`() {
        val source = FastbootGetVarAllParser.parse(
            listOf(
                "INFOpartition-size:boot: 0x1000",
                "INFOpartition-type:boot: raw",
                "INFOis-logical:boot: no",
            ),
        )
        val probe = FastbootPartitionProbe(
            name = "BOOT",
            sizeBytes = 8192L,
            type = "emmc",
            logical = true,
            attemptedFields = setOf(
                FastbootGetVarAllParser.MetadataField.SIZE,
                FastbootGetVarAllParser.MetadataField.TYPE,
                FastbootGetVarAllParser.MetadataField.LOGICAL,
            ),
            resolvedFields = setOf(
                FastbootGetVarAllParser.MetadataField.SIZE,
                FastbootGetVarAllParser.MetadataField.TYPE,
                FastbootGetVarAllParser.MetadataField.LOGICAL,
            ),
        )

        val inventory = FastbootPartitionInventoryBuilder.build(source, pointProbes = listOf(probe))
        val boot = inventory.partition("boot")!!

        assertEquals(8192L, boot.sizeBytes)
        assertEquals("emmc", boot.type)
        assertEquals(true, boot.logical)
        assertEquals(FastbootStorageKind.LOGICAL, boot.storage)
        assertEquals(
            setOf(FastbootPartitionEvidenceSource.GETVAR_ALL, FastbootPartitionEvidenceSource.POINT_QUERY),
            boot.evidenceSources,
        )
        assertTrue(boot.warnings.count { it.code == "PARTITION_METADATA_CONFLICT" } == 3)
    }

    @Test
    fun `getvar all value wins a conflicting supplemental variable`() {
        val source = FastbootGetVarAllParser.parse(listOf("INFOproduct: rodin"))
        val inventory = FastbootPartitionInventoryBuilder.build(
            source = source,
            supplementalVariables = mapOf("PRODUCT" to "vayu"),
        )

        assertEquals("rodin", inventory.product)
        assertEquals("rodin", inventory.variables["product"])
        assertTrue(inventory.warnings.any { it.code == "VARIABLE_CONFLICT" })
    }

    @Test
    fun `risk and base name policy preserves legacy classification`() {
        assertEquals("vendor_boot", FastbootPartitionInventoryBuilder.baseName(" Vendor_Boot_B "))
        assertEquals(FastbootPartitionRisk.NORMAL, FastbootPartitionInventoryBuilder.riskOf("vendor_boot_b"))
        assertEquals(FastbootPartitionRisk.ADVANCED, FastbootPartitionInventoryBuilder.riskOf("vbmeta_custom_a"))
        assertEquals(FastbootPartitionRisk.CRITICAL, FastbootPartitionInventoryBuilder.riskOf("super"))
    }

    @Test
    fun `partial and failed getvar status remains incomplete and visible`() {
        val source = FastbootGetVarAllParser.parse(
            lines = listOf("INFOpartition-size:boot: 0x1000"),
            complete = false,
            finalStatus = "FAIL",
            finalMessage = "not supported",
        )
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        assertFalse(inventory.complete)
        assertEquals("FAIL", inventory.finalStatus)
        assertEquals("not supported", inventory.finalMessage)
        assertTrue(inventory.warnings.any { it.code == "PARTIAL_GETVAR_ALL" })
        assertTrue(inventory.warnings.any { it.code == "GETVAR_ALL_FINAL_STATUS" })
    }

    private fun inventoryOf(vararg lines: String): FastbootPartitionInventory =
        FastbootPartitionInventoryBuilder.build(FastbootGetVarAllParser.parse(lines.asList()))
}
