package io.github.ncorror.nekoflash.fastboot.partition

import io.github.ncorror.nekoflash.fastboot.codec.FastbootGetVarAllParser
import java.util.Locale

enum class FastbootSlotTopology {
    LEGACY_A_ONLY,
    A_B,
    UNKNOWN,
}

enum class FastbootPartitionRisk {
    NORMAL,
    ADVANCED,
    CRITICAL,
}

enum class FastbootStorageKind {
    PHYSICAL,
    LOGICAL,
    UNKNOWN,
}

enum class FastbootSlotBinding {
    SLOT_A,
    SLOT_B,
    UNSLOTTED,
    SLOT_FAMILY_BASE,
    UNKNOWN,
}

enum class FastbootPartitionEvidenceSource {
    GETVAR_ALL,
    POINT_QUERY,
}

enum class FastbootWarningSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class FastbootPartitionWarning(
    val code: String,
    val message: String,
    val severity: FastbootWarningSeverity = FastbootWarningSeverity.WARNING,
    val partitionName: String? = null,
)

data class FastbootPartitionProbe(
    val name: String,
    val sizeBytes: Long? = null,
    val type: String? = null,
    val logical: Boolean? = null,
    val hasSlot: Boolean? = null,
    val attemptedFields: Set<FastbootGetVarAllParser.MetadataField> = emptySet(),
    val resolvedFields: Set<FastbootGetVarAllParser.MetadataField> = emptySet(),
) {
    val hasConcreteEvidence: Boolean
        get() = resolvedFields.any { metadataField ->
            metadataField == FastbootGetVarAllParser.MetadataField.SIZE ||
                metadataField == FastbootGetVarAllParser.MetadataField.TYPE ||
                metadataField == FastbootGetVarAllParser.MetadataField.LOGICAL
        }
}

data class FastbootPartitionEntry(
    val name: String,
    val baseName: String,
    val slotBinding: FastbootSlotBinding,
    val sizeBytes: Long?,
    val type: String?,
    val logical: Boolean?,
    val storage: FastbootStorageKind,
    val hasSlot: Boolean?,
    val risk: FastbootPartitionRisk,
    val evidenceSources: Set<FastbootPartitionEvidenceSource>,
    val missingFields: Set<FastbootGetVarAllParser.MetadataField>,
    val warnings: List<FastbootPartitionWarning>,
)

data class FastbootPartitionInventory(
    val product: String?,
    val topology: FastbootSlotTopology,
    val currentSlot: String?,
    val entries: List<FastbootPartitionEntry>,
    val slotFamilies: Map<String, Boolean?>,
    val variables: Map<String, String>,
    val complete: Boolean,
    val finalStatus: String,
    val finalMessage: String?,
    val warnings: List<FastbootPartitionWarning>,
    val duplicateMetadataCount: Int,
    val pointQueryCount: Int,
    val unresolvedPointQueryCount: Int,
) {
    fun partition(name: String): FastbootPartitionEntry? =
        entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun filtered(
        query: String = "",
        risk: FastbootPartitionRisk? = null,
        storage: FastbootStorageKind? = null,
    ): List<FastbootPartitionEntry> {
        val normalizedQuery = query.trim().lowercase(Locale.US)
        return entries.filter { entry ->
            val queryMatches = normalizedQuery.isBlank() ||
                entry.name.contains(normalizedQuery) ||
                entry.baseName.contains(normalizedQuery) ||
                entry.type?.lowercase(Locale.US)?.contains(normalizedQuery) == true

            queryMatches &&
                (risk == null || entry.risk == risk) &&
                (storage == null || entry.storage == storage)
        }
    }
}
