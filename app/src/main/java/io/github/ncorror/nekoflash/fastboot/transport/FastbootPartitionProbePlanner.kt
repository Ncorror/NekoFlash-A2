package io.github.ncorror.nekoflash.fastboot.transport

import java.util.Locale

/**
 * Bounded read-only metadata backfill planner migrated from supplied legacy.
 *
 * The plan never accepts arbitrary user commands. It can only ask fixed partition
 * metadata variables for names already present in the manual snapshot/inventory, plus
 * the same tiny legacy fallback set when no concrete partition exists at all.
 */
internal object FastbootPartitionProbePlanner {
    const val MAX_POINT_QUERIES = 24

    data class Request(
        val partition: String,
        val field: FastbootGetVarAllPlan.MetadataField,
    ) {
        val variableName: String = when (field) {
            FastbootGetVarAllPlan.MetadataField.SIZE -> "partition-size:$partition"
            FastbootGetVarAllPlan.MetadataField.TYPE -> "partition-type:$partition"
            FastbootGetVarAllPlan.MetadataField.LOGICAL -> "is-logical:$partition"
            FastbootGetVarAllPlan.MetadataField.HAS_SLOT -> "has-slot:$partition"
        }
    }

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

    fun plan(
        source: FastbootGetVarAllPlan.Snapshot,
        inventory: FastbootPartitionInventory.Snapshot,
        maxQueries: Int = MAX_POINT_QUERIES,
        discoveryPartitions: List<String> = emptyList(),
    ): Plan {
        require(maxQueries >= 0)
        val requests = linkedSetOf<Request>()

        fun addMissingFields(name: String, existing: FastbootPartitionInventory.Entry?) {
            if (existing?.sizeBytes == null) requests += Request(name, FastbootGetVarAllPlan.MetadataField.SIZE)
            if (existing?.type.isNullOrBlank()) requests += Request(name, FastbootGetVarAllPlan.MetadataField.TYPE)
            if (existing?.logical == null) requests += Request(name, FastbootGetVarAllPlan.MetadataField.LOGICAL)
            if (existing != null && existing.slotBinding == FastbootPartitionInventory.SlotBinding.UNKNOWN) {
                requests += Request(existing.baseName, FastbootGetVarAllPlan.MetadataField.HAS_SLOT)
            } else if (
                existing == null &&
                inventory.topology == FastbootPartitionInventory.SlotTopology.UNKNOWN &&
                !name.endsWith("_a") &&
                !name.endsWith("_b")
            ) {
                requests += Request(name, FastbootGetVarAllPlan.MetadataField.HAS_SLOT)
            }
        }

        inventory.entries.forEach { entry -> addMissingFields(entry.name, entry) }

        val normalizedDiscoveryPartitions = discoveryPartitions
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.matches(Regex("^[a-z0-9._-]{1,64}$")) }
            .distinct()
        normalizedDiscoveryPartitions.forEach { name ->
            addMissingFields(name, inventory.partition(name))
        }

        // Family-only has-slot metadata is not a partition. Probe concrete names only;
        // a final entry still requires size/type/is-logical evidence.
        source.partitions
            .filter { partition ->
                val concrete = partition.fields.any {
                    it == FastbootGetVarAllPlan.MetadataField.SIZE ||
                        it == FastbootGetVarAllPlan.MetadataField.TYPE ||
                        it == FastbootGetVarAllPlan.MetadataField.LOGICAL
                }
                !concrete && FastbootGetVarAllPlan.MetadataField.HAS_SLOT in partition.fields
            }
            .forEach { family ->
                val base = family.name
                when {
                    inventory.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY || family.hasSlot == false -> {
                        addMissingFields(base, inventory.partition(base))
                    }
                    family.hasSlot == true -> {
                        addMissingFields("${base}_a", inventory.partition("${base}_a"))
                        addMissingFields("${base}_b", inventory.partition("${base}_b"))
                    }
                }
            }

        var discoveryFallbackUsed = false
        if (inventory.entries.isEmpty()) {
            discoveryFallbackUsed = true
            fallbackDiscoveryBases.forEach { base ->
                when (inventory.topology) {
                    FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> addMissingFields(base, null)
                    FastbootPartitionInventory.SlotTopology.A_B -> {
                        addMissingFields("${base}_a", null)
                        addMissingFields("${base}_b", null)
                    }
                    FastbootPartitionInventory.SlotTopology.UNKNOWN -> addMissingFields(base, null)
                }
            }
        }

        val discoveryRank = normalizedDiscoveryPartitions
            .withIndex()
            .associate { it.value to it.index }
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

    private fun riskPriority(partition: String): Int = when (FastbootPartitionInventory.riskTier(partition)) {
        FastbootPartitionInventory.RiskTier.NORMAL -> 0
        FastbootPartitionInventory.RiskTier.ADVANCED -> 1
        FastbootPartitionInventory.RiskTier.CRITICAL -> 2
    }

    private fun fieldPriority(field: FastbootGetVarAllPlan.MetadataField): Int = when (field) {
        FastbootGetVarAllPlan.MetadataField.SIZE -> 0
        FastbootGetVarAllPlan.MetadataField.TYPE -> 1
        FastbootGetVarAllPlan.MetadataField.LOGICAL -> 2
        FastbootGetVarAllPlan.MetadataField.HAS_SLOT -> 3
    }
}
