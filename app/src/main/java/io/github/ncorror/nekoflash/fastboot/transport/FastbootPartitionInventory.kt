package io.github.ncorror.nekoflash.fastboot.transport

import java.util.Locale

/**
 * Privacy-safe read-only partition/topology model derived from an explicit manual
 * `getvar:all` snapshot plus bounded legacy metadata backfill point queries.
 *
 * `has-slot:<base>` alone never invents a concrete partition, point queries may only
 * fill metadata for planner-selected names, and vayu stays legacy A-only even when a
 * bootloader exposes noisy slot metadata. No result from this model authorizes a write.
 */
internal object FastbootPartitionInventory {
    enum class SlotTopology {
        LEGACY_A_ONLY,
        A_B,
        UNKNOWN,
    }

    enum class RiskTier {
        NORMAL,
        ADVANCED,
        CRITICAL,
    }

    enum class StorageKind {
        PHYSICAL,
        LOGICAL,
        UNKNOWN,
    }

    enum class SlotBinding {
        SLOT_A,
        SLOT_B,
        UNSLOTTED,
        SLOT_FAMILY_BASE,
        UNKNOWN,
    }

    enum class WarningSeverity {
        INFO,
        WARNING,
        CRITICAL,
    }

    data class Warning(
        val code: String,
        val severity: WarningSeverity = WarningSeverity.WARNING,
        val partitionName: String? = null,
    )

    data class PointProbe(
        val name: String,
        val sizeBytes: Long? = null,
        val type: String? = null,
        val logical: Boolean? = null,
        val hasSlot: Boolean? = null,
        val attemptedFields: Set<FastbootGetVarAllPlan.MetadataField> = emptySet(),
        val resolvedFields: Set<FastbootGetVarAllPlan.MetadataField> = emptySet(),
    ) {
        val hasConcreteEvidence: Boolean
            get() = resolvedFields.any {
                it == FastbootGetVarAllPlan.MetadataField.SIZE ||
                    it == FastbootGetVarAllPlan.MetadataField.TYPE ||
                    it == FastbootGetVarAllPlan.MetadataField.LOGICAL
            }
    }

    data class Entry(
        val name: String,
        val baseName: String,
        val slotBinding: SlotBinding,
        val sizeBytes: Long?,
        val type: String?,
        val logical: Boolean?,
        val storage: StorageKind,
        val hasSlot: Boolean?,
        val risk: RiskTier,
        val missingFields: Set<FastbootGetVarAllPlan.MetadataField>,
    )

    data class Snapshot(
        val productReported: Boolean,
        val topology: SlotTopology,
        val currentSlot: String?,
        val entries: List<Entry>,
        val slotFamilies: Map<String, Boolean?>,
        val complete: Boolean,
        val finalStatus: String,
        val warnings: List<Warning>,
        val duplicateMetadataCount: Int,
        val pointQueryCount: Int,
        val unresolvedPointQueryCount: Int,
    ) {
        fun partition(name: String): Entry? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        fun summary(): Summary = Summary(
            productReported = productReported,
            topology = topology,
            currentSlot = currentSlot,
            entryCount = entries.size,
            slotFamilyCount = slotFamilies.size,
            physicalCount = entries.count { it.storage == StorageKind.PHYSICAL },
            logicalCount = entries.count { it.storage == StorageKind.LOGICAL },
            unknownStorageCount = entries.count { it.storage == StorageKind.UNKNOWN },
            normalCount = entries.count { it.risk == RiskTier.NORMAL },
            advancedCount = entries.count { it.risk == RiskTier.ADVANCED },
            criticalCount = entries.count { it.risk == RiskTier.CRITICAL },
            incompleteEntryCount = entries.count { it.missingFields.isNotEmpty() },
            warningCount = warnings.size,
            duplicateMetadataCount = duplicateMetadataCount,
            pointQueryCount = pointQueryCount,
            unresolvedPointQueryCount = unresolvedPointQueryCount,
            complete = complete,
        )
    }

    /** Safe aggregate for diagnostics; it contains no raw getvar values or serial. */
    data class Summary(
        val productReported: Boolean,
        val topology: SlotTopology,
        val currentSlot: String?,
        val entryCount: Int,
        val slotFamilyCount: Int,
        val physicalCount: Int,
        val logicalCount: Int,
        val unknownStorageCount: Int,
        val normalCount: Int,
        val advancedCount: Int,
        val criticalCount: Int,
        val incompleteEntryCount: Int,
        val warningCount: Int,
        val duplicateMetadataCount: Int,
        val pointQueryCount: Int,
        val unresolvedPointQueryCount: Int,
        val complete: Boolean,
    )

    private data class MutableEntry(
        val name: String,
        var sizeBytes: Long? = null,
        var type: String? = null,
        var logical: Boolean? = null,
        var hasSlot: Boolean? = null,
    )

    private val legacyAOnlyProducts = setOf("vayu")

    private val normalBaseNames = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vendor_kernel_boot",
        "recovery",
        "dtbo",
    )

    private val legacyTopologyEvidenceBases = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "recovery",
    )

    private val advancedBaseNames = setOf(
        "vbmeta",
        "vbmeta_system",
        "vbmeta_vendor",
        "logo",
        "splash",
        "modem",
        "radio",
    )

    fun from(
        source: FastbootGetVarAllPlan.Snapshot,
        fallbackProduct: String? = null,
        supplementalVariables: Map<String, String> = emptyMap(),
        pointProbes: List<PointProbe> = emptyList(),
        collectionWarnings: List<Warning> = emptyList(),
    ): Snapshot {
        val warnings = collectionWarnings.toMutableList()

        fun sourceValue(name: String): String? = source.variables[name]
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        fun supplementalValue(name: String): String? = supplementalVariables[name]
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        fun resolvedValue(name: String): String? {
            val primary = sourceValue(name)
            val supplemental = supplementalValue(name)
            if (primary != null && supplemental != null && !primary.equals(supplemental, ignoreCase = true)) {
                warnings += Warning(code = "VARIABLE_CONFLICT", severity = WarningSeverity.WARNING)
            }
            return primary ?: supplemental
        }

        val product = sourceValue("product")
            ?.lowercase(Locale.US)
            ?: fallbackProduct?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val currentSlot = resolvedValue("current-slot")
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it == "a" || it == "b" }
        val slotCount = resolvedValue("slot-count")?.toIntOrNull()

        val slotFamilies = linkedMapOf<String, Boolean?>()
        val mutableEntries = linkedMapOf<String, MutableEntry>()

        source.partitions.forEach { partition ->
            val name = partition.name.trim().lowercase(Locale.US)
            if (name.isBlank()) return@forEach
            if (FastbootGetVarAllPlan.MetadataField.HAS_SLOT in partition.fields) {
                slotFamilies[name] = partition.hasSlot
            }
            val hasConcreteEvidence = partition.fields.any {
                it == FastbootGetVarAllPlan.MetadataField.SIZE ||
                    it == FastbootGetVarAllPlan.MetadataField.TYPE ||
                    it == FastbootGetVarAllPlan.MetadataField.LOGICAL
            }
            if (!hasConcreteEvidence) return@forEach

            mutableEntries[name] = MutableEntry(
                name = name,
                sizeBytes = partition.sizeBytes,
                type = partition.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() },
                logical = partition.logical,
                hasSlot = partition.hasSlot,
            )
        }

        fun mergeField(
            entry: MutableEntry,
            existing: Any?,
            incoming: Any?,
            applyIncoming: () -> Unit,
        ) {
            if (incoming == null) return
            if (existing != null && existing != incoming) {
                warnings += Warning(
                    code = "PARTITION_METADATA_CONFLICT",
                    severity = WarningSeverity.WARNING,
                    partitionName = entry.name,
                )
            }
            applyIncoming()
        }

        pointProbes.forEach { probe ->
            val name = probe.name.trim().lowercase(Locale.US)
            if (name.isBlank()) return@forEach
            if (FastbootGetVarAllPlan.MetadataField.HAS_SLOT in probe.resolvedFields) {
                slotFamilies[name] = probe.hasSlot
            }
            if (!probe.hasConcreteEvidence) return@forEach

            val entry = mutableEntries.getOrPut(name) { MutableEntry(name) }
            mergeField(entry, entry.sizeBytes, probe.sizeBytes) {
                entry.sizeBytes = probe.sizeBytes
            }
            val normalizedType = probe.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            mergeField(entry, entry.type, normalizedType) {
                entry.type = normalizedType
            }
            mergeField(entry, entry.logical, probe.logical) {
                entry.logical = probe.logical
            }
            mergeField(entry, entry.hasSlot, probe.hasSlot) {
                entry.hasSlot = probe.hasSlot
            }
        }

        // `has-slot:<base>` is family metadata. It may annotate an already concrete
        // entry, but it must never manufacture a partition name by itself.
        mutableEntries.values.forEach { entry ->
            if (entry.hasSlot == null) {
                slotFamilies[baseName(entry.name)]?.let { entry.hasSlot = it }
            }
        }

        val topology = detectTopology(
            product = product,
            slotCount = slotCount,
            currentSlot = currentSlot,
            entries = mutableEntries.values.toList(),
            slotFamilies = slotFamilies,
        )

        if (!source.complete) {
            warnings += Warning(code = "PARTIAL_GETVAR_ALL", severity = WarningSeverity.WARNING)
        }
        if (!source.finalStatus.equals("OKAY", ignoreCase = true)) {
            warnings += Warning(code = "GETVAR_ALL_FINAL_STATUS", severity = WarningSeverity.WARNING)
        }
        source.duplicateVariables.forEach { duplicate ->
            warnings += Warning(
                code = if (duplicate.conflicting) "CONFLICTING_DUPLICATE" else "DUPLICATE_METADATA",
                severity = if (duplicate.conflicting) WarningSeverity.WARNING else WarningSeverity.INFO,
            )
        }

        val hasSlottedConcreteNames = mutableEntries.keys.any(::hasSlotSuffix)
        val hasPositiveFamily = slotFamilies.values.any { it == true }
        if (topology == SlotTopology.LEGACY_A_ONLY && (hasSlottedConcreteNames || hasPositiveFamily)) {
            warnings += Warning(code = "LEGACY_SLOT_CONTRADICTION", severity = WarningSeverity.CRITICAL)
        }
        if (topology == SlotTopology.A_B && currentSlot == null) {
            warnings += Warning(code = "CURRENT_SLOT_UNKNOWN", severity = WarningSeverity.WARNING)
        }
        if (topology == SlotTopology.UNKNOWN) {
            warnings += Warning(code = "SLOT_TOPOLOGY_UNKNOWN", severity = WarningSeverity.WARNING)
        }
        if (mutableEntries.isEmpty()) {
            warnings += Warning(code = "NO_CONCRETE_PARTITIONS", severity = WarningSeverity.WARNING)
        }

        val entries = mutableEntries.values.map { mutable ->
            val missing = linkedSetOf<FastbootGetVarAllPlan.MetadataField>()
            if (mutable.sizeBytes == null) missing += FastbootGetVarAllPlan.MetadataField.SIZE
            if (mutable.type.isNullOrBlank()) missing += FastbootGetVarAllPlan.MetadataField.TYPE
            if (mutable.logical == null) missing += FastbootGetVarAllPlan.MetadataField.LOGICAL

            Entry(
                name = mutable.name,
                baseName = baseName(mutable.name),
                slotBinding = slotBinding(mutable.name, mutable.hasSlot, topology),
                sizeBytes = mutable.sizeBytes,
                type = mutable.type,
                logical = mutable.logical,
                storage = when (mutable.logical) {
                    true -> StorageKind.LOGICAL
                    false -> StorageKind.PHYSICAL
                    null -> StorageKind.UNKNOWN
                },
                hasSlot = mutable.hasSlot,
                risk = riskTier(mutable.name),
                missingFields = missing,
            )
        }.sortedBy { it.name }

        val entryWarnings = entries
            .filter { it.missingFields.isNotEmpty() }
            .map {
                Warning(
                    code = "PARTITION_METADATA_INCOMPLETE",
                    severity = WarningSeverity.INFO,
                    partitionName = it.name,
                )
            }

        val pointQueryCount = pointProbes.sumOf { it.attemptedFields.size }
        val unresolvedPointQueryCount = pointProbes.sumOf {
            (it.attemptedFields - it.resolvedFields).size
        }

        return Snapshot(
            productReported = product != null,
            topology = topology,
            currentSlot = currentSlot,
            entries = entries,
            slotFamilies = slotFamilies.toSortedMap(),
            complete = source.complete && source.finalStatus.equals("OKAY", ignoreCase = true),
            finalStatus = source.finalStatus,
            warnings = (warnings + entryWarnings).distinctBy { listOf(it.code, it.partitionName, it.severity.name) },
            duplicateMetadataCount = source.duplicateVariables.size,
            pointQueryCount = pointQueryCount,
            unresolvedPointQueryCount = unresolvedPointQueryCount,
        )
    }

    fun riskTier(partitionName: String): RiskTier {
        val base = baseName(partitionName)
        return when {
            base in normalBaseNames -> RiskTier.NORMAL
            base in advancedBaseNames || base.startsWith("vbmeta_") -> RiskTier.ADVANCED
            else -> RiskTier.CRITICAL
        }
    }

    fun baseName(partitionName: String): String {
        val normalized = partitionName.trim().lowercase(Locale.US)
        return when {
            normalized.endsWith("_a") || normalized.endsWith("_b") -> normalized.dropLast(2)
            else -> normalized
        }
    }

    private fun isLegacyAOnlyProduct(product: String?): Boolean =
        product?.trim()?.lowercase(Locale.US) in legacyAOnlyProducts

    private fun hasSlotSuffix(name: String): Boolean = name.endsWith("_a") || name.endsWith("_b")

    private fun slotBinding(
        name: String,
        hasSlot: Boolean?,
        topology: SlotTopology,
    ): SlotBinding = when {
        topology == SlotTopology.LEGACY_A_ONLY -> SlotBinding.UNSLOTTED
        name.endsWith("_a") -> SlotBinding.SLOT_A
        name.endsWith("_b") -> SlotBinding.SLOT_B
        hasSlot == false -> SlotBinding.UNSLOTTED
        hasSlot == true -> SlotBinding.SLOT_FAMILY_BASE
        else -> SlotBinding.UNKNOWN
    }

    private fun detectTopology(
        product: String?,
        slotCount: Int?,
        currentSlot: String?,
        entries: List<MutableEntry>,
        slotFamilies: Map<String, Boolean?>,
    ): SlotTopology {
        if (isLegacyAOnlyProduct(product)) return SlotTopology.LEGACY_A_ONLY

        val hasConcreteSlottedName = entries.any { hasSlotSuffix(it.name) }
        val hasPositiveSlotEvidence = slotFamilies.values.any { it == true } || entries.any { it.hasSlot == true }

        if ((slotCount ?: 0) >= 2 || currentSlot == "a" || currentSlot == "b" ||
            hasConcreteSlottedName || hasPositiveSlotEvidence
        ) {
            return SlotTopology.A_B
        }

        if (slotCount == 0 || slotCount == 1) return SlotTopology.LEGACY_A_ONLY

        val explicitNoSlotFamilies = slotFamilies.count { (name, value) ->
            value == false && baseName(name) in legacyTopologyEvidenceBases
        }
        if (explicitNoSlotFamilies >= 2 && !hasPositiveSlotEvidence) {
            return SlotTopology.LEGACY_A_ONLY
        }

        return SlotTopology.UNKNOWN
    }
}
