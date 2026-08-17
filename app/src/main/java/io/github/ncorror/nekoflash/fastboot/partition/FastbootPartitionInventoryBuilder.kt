package io.github.ncorror.nekoflash.fastboot.partition

import io.github.ncorror.nekoflash.fastboot.codec.FastbootGetVarAllParser
import java.util.Locale

/**
 * Builds an informational partition inventory from read-only Fastboot metadata.
 * A name is exposed only after concrete device evidence confirms it.
 */
object FastbootPartitionInventoryBuilder {
    private data class MutableEntry(
        val name: String,
        var sizeBytes: Long? = null,
        var type: String? = null,
        var logical: Boolean? = null,
        var hasSlot: Boolean? = null,
        val evidenceSources: MutableSet<FastbootPartitionEvidenceSource> = linkedSetOf(),
        val warnings: MutableList<FastbootPartitionWarning> = mutableListOf(),
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

    fun build(
        source: FastbootGetVarAllParser.Result,
        fallbackProduct: String? = null,
        supplementalVariables: Map<String, String> = emptyMap(),
        pointProbes: List<FastbootPartitionProbe> = emptyList(),
        collectionWarnings: List<FastbootPartitionWarning> = emptyList(),
    ): FastbootPartitionInventory {
        val warnings = collectionWarnings.toMutableList()
        val variables = mergeVariables(source, supplementalVariables, warnings)
        val product = product(variables, fallbackProduct)
        val currentSlot = currentSlot(variables)
        val slotFamilies = linkedMapOf<String, Boolean?>()
        val entries = linkedMapOf<String, MutableEntry>()

        collectGetVarEntries(source, slotFamilies, entries)
        collectPointProbeEntries(pointProbes, slotFamilies, entries)
        propagateFamilySlotMetadata(slotFamilies, entries)

        val topology = detectTopology(product, variables, entries.values.toList(), slotFamilies)
        collectSourceWarnings(source, warnings)
        collectTopologyWarnings(topology, currentSlot, slotFamilies, entries, warnings)

        val immutableEntries = entries.values
            .map { entry -> entry.toImmutable(topology) }
            .sortedBy { it.name }

        val allWarnings = (warnings + immutableEntries.flatMap { it.warnings })
            .distinctBy { warning -> listOf(warning.code, warning.partitionName, warning.message) }

        return FastbootPartitionInventory(
            product = product,
            topology = topology,
            currentSlot = currentSlot,
            entries = immutableEntries,
            slotFamilies = slotFamilies.toSortedMap(),
            variables = variables.toMap(),
            complete = source.complete && source.finalStatus.equals("OKAY", ignoreCase = true),
            finalStatus = source.finalStatus,
            finalMessage = source.finalMessage,
            warnings = allWarnings,
            duplicateMetadataCount = source.duplicateVariables.size,
            pointQueryCount = pointProbes.sumOf { it.attemptedFields.size },
            unresolvedPointQueryCount = pointProbes.sumOf { probe ->
                probe.attemptedFields.minus(probe.resolvedFields).size
            },
        )
    }

    fun isLegacyAOnlyProduct(product: String?): Boolean =
        product?.trim()?.lowercase(Locale.US) in legacyAOnlyProducts

    fun riskOf(partitionName: String): FastbootPartitionRisk {
        val base = baseName(partitionName)
        return when {
            base in normalBaseNames -> FastbootPartitionRisk.NORMAL
            base in advancedBaseNames || base.startsWith("vbmeta_") -> FastbootPartitionRisk.ADVANCED
            else -> FastbootPartitionRisk.CRITICAL
        }
    }

    fun baseName(partitionName: String): String {
        val normalized = normalizeName(partitionName)
        return if (hasSlotSuffix(normalized)) normalized.dropLast(2) else normalized
    }

    private fun mergeVariables(
        source: FastbootGetVarAllParser.Result,
        supplementalVariables: Map<String, String>,
        warnings: MutableList<FastbootPartitionWarning>,
    ): LinkedHashMap<String, String> {
        val variables = linkedMapOf<String, String>()
        source.variables.forEach { (rawKey, rawValue) ->
            variables[normalizeName(rawKey)] = rawValue.trim()
        }

        supplementalVariables.forEach { (rawKey, rawValue) ->
            val key = normalizeName(rawKey)
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank()) return@forEach

            val existing = variables[key]
            if (existing == null) {
                variables[key] = value
            } else if (!existing.equals(value, ignoreCase = true)) {
                warnings += FastbootPartitionWarning(
                    code = "VARIABLE_CONFLICT",
                    message = "$key differs between getvar:all ($existing) and point diagnostics ($value). " +
                        "The getvar:all value was kept for display.",
                )
            }
        }
        return variables
    }

    private fun product(variables: Map<String, String>, fallbackProduct: String?): String? =
        variables["product"]
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackProduct?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }

    private fun currentSlot(variables: Map<String, String>): String? =
        variables["current-slot"]
            ?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it == "a" || it == "b" }

    private fun collectGetVarEntries(
        source: FastbootGetVarAllParser.Result,
        slotFamilies: MutableMap<String, Boolean?>,
        entries: MutableMap<String, MutableEntry>,
    ) {
        source.partitions.forEach { partition ->
            val name = normalizeName(partition.name)
            if (name.isBlank()) return@forEach

            if (FastbootGetVarAllParser.MetadataField.HAS_SLOT in partition.metadataFields) {
                slotFamilies[name] = partition.hasSlot
            }
            if (!partition.hasConcreteEvidence) return@forEach

            val entry = entries.getOrPut(name) { MutableEntry(name) }
            entry.sizeBytes = partition.sizeBytes
            entry.type = partition.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            entry.logical = partition.logical
            entry.hasSlot = partition.hasSlot
            entry.evidenceSources += FastbootPartitionEvidenceSource.GETVAR_ALL
        }
    }

    private fun collectPointProbeEntries(
        pointProbes: List<FastbootPartitionProbe>,
        slotFamilies: MutableMap<String, Boolean?>,
        entries: MutableMap<String, MutableEntry>,
    ) {
        pointProbes.forEach { probe ->
            val name = normalizeName(probe.name)
            if (name.isBlank()) return@forEach

            if (FastbootGetVarAllParser.MetadataField.HAS_SLOT in probe.resolvedFields) {
                slotFamilies[name] = probe.hasSlot
            }
            if (!probe.hasConcreteEvidence) return@forEach

            val entry = entries.getOrPut(name) { MutableEntry(name) }
            mergeField(entry, "size", entry.sizeBytes, probe.sizeBytes) { entry.sizeBytes = probe.sizeBytes }
            mergeField(
                entry,
                "type",
                entry.type,
                probe.type?.trim()?.lowercase(Locale.US),
            ) {
                entry.type = probe.type?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            }
            mergeField(entry, "logical", entry.logical, probe.logical) { entry.logical = probe.logical }
            mergeField(entry, "has-slot", entry.hasSlot, probe.hasSlot) { entry.hasSlot = probe.hasSlot }
            entry.evidenceSources += FastbootPartitionEvidenceSource.POINT_QUERY
        }
    }

    private fun mergeField(
        entry: MutableEntry,
        fieldName: String,
        existing: Any?,
        incoming: Any?,
        applyIncoming: () -> Unit,
    ) {
        if (incoming == null) return
        if (existing == null) {
            applyIncoming()
            return
        }
        if (existing != incoming) {
            entry.warnings += FastbootPartitionWarning(
                code = "PARTITION_METADATA_CONFLICT",
                message = "Field $fieldName for partition ${entry.name} differs in source point-query: " +
                    "$existing versus $incoming. The newer point value was used.",
                partitionName = entry.name,
            )
            applyIncoming()
        }
    }

    private fun propagateFamilySlotMetadata(
        slotFamilies: Map<String, Boolean?>,
        entries: MutableMap<String, MutableEntry>,
    ) {
        entries.values.forEach { entry ->
            if (entry.hasSlot == null) {
                slotFamilies[baseName(entry.name)]?.let { familyHasSlot ->
                    entry.hasSlot = familyHasSlot
                }
            }
        }
    }

    private fun collectSourceWarnings(
        source: FastbootGetVarAllParser.Result,
        warnings: MutableList<FastbootPartitionWarning>,
    ) {
        if (!source.complete) {
            warnings += FastbootPartitionWarning(
                code = "PARTIAL_GETVAR_ALL",
                message = "getvar:all completed partially; the list may be incomplete.",
            )
        }
        if (!source.finalStatus.equals("OKAY", ignoreCase = true)) {
            warnings += FastbootPartitionWarning(
                code = "GETVAR_ALL_FINAL_STATUS",
                message = "Final getvar:all status: ${source.finalStatus}" +
                    source.finalMessage?.let { " ($it)" }.orEmpty(),
            )
        }
        source.duplicateVariables.forEach { duplicate ->
            warnings += FastbootPartitionWarning(
                code = if (duplicate.conflicting) "CONFLICTING_DUPLICATE" else "DUPLICATE_METADATA",
                message = if (duplicate.conflicting) {
                    "Variable ${duplicate.name} was repeated with different values: ${duplicate.values.joinToString()}"
                } else {
                    "Variable ${duplicate.name} was repeated ${duplicate.values.size} times."
                },
                severity = if (duplicate.conflicting) {
                    FastbootWarningSeverity.WARNING
                } else {
                    FastbootWarningSeverity.INFO
                },
            )
        }
    }

    private fun collectTopologyWarnings(
        topology: FastbootSlotTopology,
        currentSlot: String?,
        slotFamilies: Map<String, Boolean?>,
        entries: Map<String, MutableEntry>,
        warnings: MutableList<FastbootPartitionWarning>,
    ) {
        val hasSlottedConcreteNames = entries.keys.any(::hasSlotSuffix)
        val hasPositiveFamily = slotFamilies.values.any { it == true }

        if (topology == FastbootSlotTopology.LEGACY_A_ONLY && (hasSlottedConcreteNames || hasPositiveFamily)) {
            warnings += FastbootPartitionWarning(
                code = "LEGACY_SLOT_CONTRADICTION",
                message = "Device is detected as legacy A-only, but the bootloader reported conflicting A/B data. " +
                    "Suffixes are not created or selected automatically.",
                severity = FastbootWarningSeverity.CRITICAL,
            )
        }
        if (topology == FastbootSlotTopology.A_B && currentSlot == null) {
            warnings += FastbootPartitionWarning(
                code = "CURRENT_SLOT_UNKNOWN",
                message = "A/B layout is confirmed, but the current slot is not detected.",
            )
        }
        if (topology == FastbootSlotTopology.UNKNOWN) {
            warnings += FastbootPartitionWarning(
                code = "SLOT_TOPOLOGY_UNKNOWN",
                message = "Slot topology is not confirmed. Inventory remains informational only.",
            )
        }
        if (entries.isEmpty()) {
            warnings += FastbootPartitionWarning(
                code = "NO_CONCRETE_PARTITIONS",
                message = "Bootloader did not confirm any partition through size/type/is-logical.",
            )
        }
    }

    private fun MutableEntry.toImmutable(topology: FastbootSlotTopology): FastbootPartitionEntry {
        val missingFields = linkedSetOf<FastbootGetVarAllParser.MetadataField>()
        if (sizeBytes == null) missingFields += FastbootGetVarAllParser.MetadataField.SIZE
        if (type.isNullOrBlank()) missingFields += FastbootGetVarAllParser.MetadataField.TYPE
        if (logical == null) missingFields += FastbootGetVarAllParser.MetadataField.LOGICAL

        val entryWarnings = warnings.toMutableList()
        if (missingFields.isNotEmpty()) {
            entryWarnings += FastbootPartitionWarning(
                code = "PARTITION_METADATA_INCOMPLETE",
                message = "Missing fields: ${missingFields.joinToString { it.name.lowercase(Locale.US) }}.",
                severity = FastbootWarningSeverity.INFO,
                partitionName = name,
            )
        }

        return FastbootPartitionEntry(
            name = name,
            baseName = baseName(name),
            slotBinding = slotBinding(name, hasSlot, topology),
            sizeBytes = sizeBytes,
            type = type,
            logical = logical,
            storage = when (logical) {
                true -> FastbootStorageKind.LOGICAL
                false -> FastbootStorageKind.PHYSICAL
                null -> FastbootStorageKind.UNKNOWN
            },
            hasSlot = hasSlot,
            risk = riskOf(name),
            evidenceSources = evidenceSources.toSet(),
            missingFields = missingFields,
            warnings = entryWarnings,
        )
    }

    private fun slotBinding(
        name: String,
        hasSlot: Boolean?,
        topology: FastbootSlotTopology,
    ): FastbootSlotBinding = when {
        topology == FastbootSlotTopology.LEGACY_A_ONLY -> FastbootSlotBinding.UNSLOTTED
        name.endsWith("_a") -> FastbootSlotBinding.SLOT_A
        name.endsWith("_b") -> FastbootSlotBinding.SLOT_B
        hasSlot == false -> FastbootSlotBinding.UNSLOTTED
        hasSlot == true -> FastbootSlotBinding.SLOT_FAMILY_BASE
        else -> FastbootSlotBinding.UNKNOWN
    }

    private fun detectTopology(
        product: String?,
        variables: Map<String, String>,
        entries: List<MutableEntry>,
        slotFamilies: Map<String, Boolean?>,
    ): FastbootSlotTopology {
        if (isLegacyAOnlyProduct(product)) return FastbootSlotTopology.LEGACY_A_ONLY

        val slotCount = variables["slot-count"]?.trim()?.toIntOrNull()
        val currentSlot = variables["current-slot"]
            ?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
        val hasConcreteSlottedName = entries.any { hasSlotSuffix(it.name) }
        val hasPositiveSlotEvidence = slotFamilies.values.any { it == true } || entries.any { it.hasSlot == true }

        if (
            (slotCount ?: 0) >= 2 ||
            currentSlot == "a" ||
            currentSlot == "b" ||
            hasConcreteSlottedName ||
            hasPositiveSlotEvidence
        ) {
            return FastbootSlotTopology.A_B
        }

        if (slotCount == 0 || slotCount == 1) return FastbootSlotTopology.LEGACY_A_ONLY

        val explicitNoSlotFamilies = slotFamilies.count { (name, value) ->
            value == false && baseName(name) in legacyTopologyEvidenceBases
        }
        if (explicitNoSlotFamilies >= 2 && !hasPositiveSlotEvidence) {
            return FastbootSlotTopology.LEGACY_A_ONLY
        }

        return FastbootSlotTopology.UNKNOWN
    }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.US)

    private fun hasSlotSuffix(name: String): Boolean =
        name.endsWith("_a") || name.endsWith("_b")
}
