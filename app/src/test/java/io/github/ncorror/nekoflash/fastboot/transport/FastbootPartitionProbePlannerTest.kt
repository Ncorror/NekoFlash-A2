package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootPartitionProbePlannerTest {
    @Test
    fun planIsBoundedToLegacyTwentyFourQueries() {
        val lines = buildList {
            add("product: vayu")
            repeat(20) { index -> add("partition-size:p$index: 0x1000") }
        }
        val source = FastbootGetVarAllPlan.parse(lines)
        val inventory = FastbootPartitionInventory.from(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory)

        assertEquals(24, plan.requests.size)
        assertTrue(plan.omittedRequestCount > 0)
        assertFalse(plan.discoveryFallbackUsed)
    }

    @Test
    fun normalPartitionsAreBackfilledBeforeAdvancedAndCritical() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: vayu",
                "partition-size:system: 0x1000",
                "partition-size:vbmeta: 0x1000",
                "partition-size:boot: 0x1000",
            ),
        )
        val inventory = FastbootPartitionInventory.from(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 4)

        assertEquals(
            listOf(
                "partition-type:boot",
                "is-logical:boot",
                "partition-type:vbmeta",
                "is-logical:vbmeta",
            ),
            plan.requests.map { it.variableName },
        )
    }

    @Test
    fun vayuFamilyOnlyMetadataProbesUnslottedBase() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: vayu",
                "has-slot:boot: yes",
            ),
        )
        val inventory = FastbootPartitionInventory.from(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 24)

        assertTrue(plan.discoveryFallbackUsed)
        assertTrue(plan.requests.any { it.variableName == "partition-size:boot" })
        assertTrue(plan.requests.any { it.variableName == "partition-type:boot" })
        assertTrue(plan.requests.any { it.variableName == "is-logical:boot" })
        assertFalse(plan.requests.any { it.partition == "boot_a" || it.partition == "boot_b" })
    }

    @Test
    fun confirmedAbFamilyProbesConcreteSuffixes() {
        val source = FastbootGetVarAllPlan.parse(
            listOf(
                "product: other",
                "slot-count: 2",
                "has-slot:boot: yes",
            ),
        )
        val inventory = FastbootPartitionInventory.from(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 48)

        assertTrue(plan.discoveryFallbackUsed)
        assertTrue(plan.requests.any { it.variableName == "partition-size:boot_a" })
        assertTrue(plan.requests.any { it.variableName == "partition-size:boot_b" })
        assertTrue(plan.requests.any { it.variableName == "partition-type:boot_a" })
        assertTrue(plan.requests.any { it.variableName == "partition-type:boot_b" })
        assertFalse(plan.requests.any { it.partition == "boot" })
    }

    @Test
    fun zeroBudgetProducesNoRequestButReportsOmissions() {
        val source = FastbootGetVarAllPlan.parse(
            listOf("product: vayu", "partition-size:boot: 0x1000"),
        )
        val inventory = FastbootPartitionInventory.from(source)

        val plan = FastbootPartitionProbePlanner.plan(source, inventory, maxQueries = 0)

        assertTrue(plan.requests.isEmpty())
        assertTrue(plan.omittedRequestCount > 0)
    }

    @Test
    fun requestSurfaceContainsOnlyPartitionMetadataGetvars() {
        val source = FastbootGetVarAllPlan.parse(
            listOf("product: vayu", "partition-size:boot: 0x1000"),
        )
        val inventory = FastbootPartitionInventory.from(source)
        val plan = FastbootPartitionProbePlanner.plan(source, inventory)

        assertTrue(plan.requests.all { request ->
            request.variableName.startsWith("partition-size:") ||
                request.variableName.startsWith("partition-type:") ||
                request.variableName.startsWith("is-logical:") ||
                request.variableName.startsWith("has-slot:")
        })
    }
}
