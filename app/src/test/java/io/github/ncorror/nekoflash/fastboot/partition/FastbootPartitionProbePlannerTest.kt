package io.github.ncorror.nekoflash.fastboot.partition

import io.github.ncorror.nekoflash.fastboot.codec.FastbootGetVarAllParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FastbootPartitionProbePlannerTest {
    @Test
    fun `existing partition requests only missing metadata and unresolved slot binding`() {
        val source = FastbootGetVarAllParser.parse(listOf("INFOpartition-size:boot: 0x1000"))
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory)

        assertEquals(
            listOf(
                request("boot", FastbootGetVarAllParser.MetadataField.TYPE),
                request("boot", FastbootGetVarAllParser.MetadataField.LOGICAL),
                request("boot", FastbootGetVarAllParser.MetadataField.HAS_SLOT),
            ),
            plan.requests,
        )
        assertFalse(plan.discoveryFallbackUsed)
    }

    @Test
    fun `discovery names are normalized validated deduplicated and prioritized`() {
        val source = FastbootGetVarAllParser.parse(
            listOf(
                "INFOpartition-size:boot: 0x1000",
                "INFOpartition-type:boot: raw",
                "INFOis-logical:boot: no",
            ),
        )
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(
            source = source,
            inventory = inventory,
            maxQueries = 5,
            discoveryPartitions = listOf(" Vendor_Boot ", "../bad", "SUPER", "super", "bad name"),
        )

        assertEquals(
            listOf(
                request("vendor_boot", FastbootGetVarAllParser.MetadataField.SIZE),
                request("vendor_boot", FastbootGetVarAllParser.MetadataField.TYPE),
                request("vendor_boot", FastbootGetVarAllParser.MetadataField.LOGICAL),
                request("vendor_boot", FastbootGetVarAllParser.MetadataField.HAS_SLOT),
                request("super", FastbootGetVarAllParser.MetadataField.SIZE),
            ),
            plan.requests,
        )
    }

    @Test
    fun `family only positive slot metadata probes concrete a and b names`() {
        val source = FastbootGetVarAllParser.parse(
            listOf(
                "INFOslot-count: 2",
                "INFOhas-slot:boot: yes",
                "INFOpartition-size:vendor_boot_a: 0x1000",
                "INFOpartition-type:vendor_boot_a: raw",
                "INFOis-logical:vendor_boot_a: no",
            ),
        )
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 100)
        val bootRequests = plan.requests.filter { it.partition.startsWith("boot") }

        assertEquals(
            setOf("boot_a", "boot_b"),
            bootRequests.map { it.partition }.toSet(),
        )
        assertEquals(
            setOf(
                FastbootGetVarAllParser.MetadataField.SIZE,
                FastbootGetVarAllParser.MetadataField.TYPE,
                FastbootGetVarAllParser.MetadataField.LOGICAL,
            ),
            bootRequests.map { it.field }.toSet(),
        )
        assertFalse(plan.discoveryFallbackUsed)
    }

    @Test
    fun `family no slot metadata probes unsuffixed concrete name`() {
        val source = FastbootGetVarAllParser.parse(
            listOf(
                "INFOslot-count: 2",
                "INFOhas-slot:recovery: no",
                "INFOpartition-size:vendor_boot_a: 0x1000",
                "INFOpartition-type:vendor_boot_a: raw",
                "INFOis-logical:vendor_boot_a: no",
            ),
        )
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 100)
        val recoveryRequests = plan.requests.filter { it.partition.startsWith("recovery") }

        assertEquals(setOf("recovery"), recoveryRequests.map { it.partition }.toSet())
        assertTrue(recoveryRequests.none { it.field == FastbootGetVarAllParser.MetadataField.HAS_SLOT })
    }

    @Test
    fun `empty unknown inventory uses bounded fallback and reports omissions`() {
        val source = FastbootGetVarAllParser.parse(emptyList())
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 5)

        assertTrue(plan.discoveryFallbackUsed)
        assertEquals(5, plan.requests.size)
        assertEquals(27, plan.omittedRequestCount)
        assertEquals(
            listOf("boot", "dtbo", "init_boot", "recovery", "vendor_boot"),
            plan.requests.map { it.partition },
        )
        assertTrue(plan.requests.all { it.field == FastbootGetVarAllParser.MetadataField.SIZE })
    }

    @Test
    fun `a b fallback probes suffixed names only`() {
        val source = FastbootGetVarAllParser.parse(listOf("INFOslot-count: 2"))
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 100)

        assertTrue(plan.discoveryFallbackUsed)
        assertEquals(48, plan.requests.size)
        assertTrue(plan.requests.all { it.partition.endsWith("_a") || it.partition.endsWith("_b") })
        assertTrue(plan.requests.none { it.field == FastbootGetVarAllParser.MetadataField.HAS_SLOT })
    }

    @Test
    fun `zero query budget is valid and negative budget is rejected`() {
        val source = FastbootGetVarAllParser.parse(emptyList())
        val inventory = FastbootPartitionInventoryBuilder.build(source)

        val zero = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 0)
        assertTrue(zero.requests.isEmpty())
        assertEquals(32, zero.omittedRequestCount)

        try {
            FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = -1)
            fail("negative maxQueries must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun request(
        partition: String,
        field: FastbootGetVarAllParser.MetadataField,
    ) = FastbootPartitionProbePlanner.Request(partition, field)
}
