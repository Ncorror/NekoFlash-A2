package io.github.ncorror.nekoflash.fastboot.partition

import io.github.ncorror.nekoflash.fastboot.codec.FastbootGetVarAllParser
import java.util.Locale

/**
 * Plans bounded read-only point queries for metadata omitted by `getvar:all`.
 * A planned name remains only a hypothesis until the bootloader confirms it.
 */
object FastbootPartitionProbePlanner {
    data class Request(
        val partition: String,
        val field: FastbootGetVarAllParser.MetadataField,
    )

    data class Plan(
        val requests: List<Request>,
        val omittedRequestCount: Int,
        val discoveryFallbackUsed: Boolean,
    )

    private val fallbackDiscoveryBases = listOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vendor_kernel_boot",
        "recovery",
        "dtbo",
        "vbmeta",
        "super",
    )

    private val validPartitionName = Regex("^[a-z0-9._-]{1,64}$")

    fun plan(
        source: FastbootGetVarAllParser.Result,
        inventory: FastbootPartitionInventory,
        maxQueries: Int = 24,
        discoveryPartitions: List<String> = emptyList(),
    ): Plan {
        require(maxQueries >= 0)

        val requests = linkedSetOf<Request>()

        fun addMissingFields(name: String, existing: FastbootPartitionEntry?) {
            if (existing?.sizeBytes == null) {
                requests += Request(name, FastbootGetVarAllParser.MetadataField.SIZE)
            }
            if (existing?.type.isNullOrBlank()) {
                requests += Request(name, FastbootGetVarAllParser.MetadataField.TYPE)
            }
            if (existing?.logical == null) {
                requests += Request(name, FastbootGetVarAllParser.MetadataField.LOGICAL)
            }

            if (existing != null && existing.slotBinding == FastbootSlotBinding.UNKNOWN) {
                requests += Request(existing.baseName, FastbootGetVarAllParser.MetadataField.HAS_SLOT)
            } else if (
                existing == null &&
                inventory.topology == FastbootSlotTopology.UNKNOWN &&
                !hasSlotSuffix(name)
            ) {
                requests += Request(name, FastbootGetVarAllParser.MetadataField.HAS_SLOT)
            }
        }

        inventory.entries.forEach { entry -> addMissingFields(entry.name, entry) }

        val normalizedDiscoveryPartitions = discoveryPartitions
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.matches(validPartitionName) }
            .distinct()

        normalizedDiscoveryPartitions.forEach { name ->
            addMissingFields(name, inventory.partition(name))
        }

        source.partitions
            .filter { partition ->
                !partition.hasConcreteEvidence &&
                    FastbootGetVarAllParser.MetadataField.HAS_SLOT in partition.metadataFields
            }
            .forEach { family ->
                when {
                    inventory.topology == FastbootSlotTopology.LEGACY_A_ONLY || family.hasSlot == false -> {
                        addMissingFields(family.name, inventory.partition(family.name))
                    }
                    family.hasSlot == true -> {
                        addMissingFields("${family.name}_a", inventory.partition("${family.name}_a"))
                        addMissingFields("${family.name}_b", inventory.partition("${family.name}_b"))
                    }
                }
            }

        var discoveryFallbackUsed = false
        if (inventory.entries.isEmpty()) {
            discoveryFallbackUsed = true
            fallbackDiscoveryBases.forEach { base ->
                when (inventory.topology) {
                    FastbootSlotTopology.LEGACY_A_ONLY -> addMissingFields(base, null)
                    FastbootSlotTopology.A_B -> {
                        addMissingFields("${base}_a", null)
                        addMissingFields("${base}_b", null)
                    }
                    FastbootSlotTopology.UNKNOWN -> addMissingFields(base, null)
                }
            }
        }

        val discoveryRank = normalizedDiscoveryPartitions
            .withIndex()
            .associate { indexed -> indexed.value to indexed.index }

        val sorted = requests.sortedWith(
            compareBy<Request> { discoveryRank[it.partition] ?: Int.MAX_VALUE }
                .thenBy { riskPriority(it.partition) }
                .thenBy { fieldPriority(it.field) }
                .thenBy { it.partition },
        )
        val limited = sorted.take(maxQueries)

        return Plan(
            requests = limited,
            omittedRequestCount = (sorted.size - limited.size).coerceAtLeast(0),
            discoveryFallbackUsed = discoveryFallbackUsed,
        )
    }

    private fun riskPriority(partition: String): Int =
        when (FastbootPartitionInventoryBuilder.riskOf(partition)) {
            FastbootPartitionRisk.NORMAL -> 0
            FastbootPartitionRisk.ADVANCED -> 1
            FastbootPartitionRisk.CRITICAL -> 2
        }

    private fun fieldPriority(field: FastbootGetVarAllParser.MetadataField): Int = when (field) {
        FastbootGetVarAllParser.MetadataField.SIZE -> 0
        FastbootGetVarAllParser.MetadataField.TYPE -> 1
        FastbootGetVarAllParser.MetadataField.LOGICAL -> 2
        FastbootGetVarAllParser.MetadataField.HAS_SLOT -> 3
    }

    private fun hasSlotSuffix(name: String): Boolean =
        name.endsWith("_a") || name.endsWith("_b")
}
