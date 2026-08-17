package io.github.ncorror.nekoflash.fastboot.codec

import java.util.Locale

/**
 * Parses read-only `getvar:all` output without inventing partitions or slot suffixes.
 * Transport prefixes commonly emitted by bootloader-fastboot and fastbootd are ignored.
 */
object FastbootGetVarAllParser {
    enum class MetadataField {
        SIZE,
        TYPE,
        LOGICAL,
        HAS_SLOT,
    }

    data class DuplicateVariable(
        val name: String,
        val values: List<String>,
        val conflicting: Boolean,
    )

    data class PartitionMetadata(
        val name: String,
        val sizeBytes: Long? = null,
        val type: String? = null,
        val logical: Boolean? = null,
        val hasSlot: Boolean? = null,
        val metadataFields: Set<MetadataField> = emptySet(),
    ) {
        val hasConcreteEvidence: Boolean
            get() = metadataFields.any { metadataField ->
                metadataField == MetadataField.SIZE ||
                    metadataField == MetadataField.TYPE ||
                    metadataField == MetadataField.LOGICAL
            }
    }

    data class Result(
        val variables: Map<String, String>,
        val partitions: List<PartitionMetadata>,
        val complete: Boolean,
        val finalStatus: String,
        val finalMessage: String? = null,
        val ignoredLines: List<String> = emptyList(),
        val duplicateVariables: List<DuplicateVariable> = emptyList(),
    ) {
        fun value(name: String): String? = variables[normalizeName(name)]

        fun partition(name: String): PartitionMetadata? =
            partitions.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }

    private val partitionScopedVariable = Regex(
        "^(partition-size|partition-type|is-logical|has-slot|slot-successful|" +
            "slot-unbootable|slot-retry-count):([^:]+):\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(
        lines: Iterable<String>,
        complete: Boolean = true,
        finalStatus: String = "OKAY",
        finalMessage: String? = null,
    ): Result {
        val variables = linkedMapOf<String, String>()
        val allValues = linkedMapOf<String, MutableList<String>>()
        val ignoredLines = mutableListOf<String>()

        for (rawBlock in lines) {
            for (rawLine in rawBlock.lineSequence()) {
                val line = normalizeLine(rawLine)
                if (line.isBlank()) continue

                val variable = splitVariable(line)
                if (variable == null) {
                    ignoredLines += line
                    continue
                }

                val (name, value) = variable
                if (name == "all" && value.equals("done!", ignoreCase = true)) continue

                allValues.getOrPut(name) { mutableListOf() } += value
                // Preserve legacy/CLI semantics: the last value is authoritative.
                variables[name] = value
            }
        }

        val metadataByPartition = linkedMapOf<String, MutableSet<MetadataField>>()
        for (key in variables.keys) {
            val field = metadataFieldForKey(key) ?: continue
            val partitionName = key.substringAfter(':').trim().lowercase(Locale.US)
            if (partitionName.isNotBlank()) {
                metadataByPartition.getOrPut(partitionName) { linkedSetOf() } += field
            }
        }

        val partitions = metadataByPartition.keys
            .sorted()
            .map { name ->
                PartitionMetadata(
                    name = name,
                    sizeBytes = parseSize(variables["partition-size:$name"]),
                    type = variables["partition-type:$name"]?.takeIf { it.isNotBlank() },
                    logical = parseBoolean(variables["is-logical:$name"]),
                    hasSlot = parseBoolean(variables["has-slot:$name"]),
                    metadataFields = metadataByPartition.getValue(name).toSet(),
                )
            }

        val duplicates = allValues
            .filterValues { values -> values.size > 1 }
            .map { (name, values) ->
                DuplicateVariable(
                    name = name,
                    values = values.toList(),
                    conflicting = values
                        .map { it.trim().lowercase(Locale.US) }
                        .distinct()
                        .size > 1,
                )
            }

        return Result(
            variables = variables.toMap(),
            partitions = partitions,
            complete = complete,
            finalStatus = finalStatus,
            finalMessage = finalMessage?.takeIf { it.isNotBlank() },
            ignoredLines = ignoredLines,
            duplicateVariables = duplicates,
        )
    }

    fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.US)) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    fun parseSize(raw: String?): Long? {
        val value = raw?.trim()?.lowercase(Locale.US) ?: return null
        val parsed = if (value.startsWith("0x")) {
            value.removePrefix("0x").toLongOrNull(16)
        } else {
            value.toLongOrNull()
        }
        return parsed?.takeIf { it >= 0L }
    }

    private fun normalizeLine(raw: String): String {
        var value = raw.trim().replace("\u0000", "")
        while (true) {
            val withoutPrefix = when {
                value.startsWith("INFO", ignoreCase = true) -> value.drop(4).trim()
                value.startsWith("TEXT", ignoreCase = true) -> value.drop(4).trim()
                value.startsWith("(bootloader)", ignoreCase = true) ->
                    value.drop("(bootloader)".length).trim()
                else -> value
            }
            if (withoutPrefix == value) return value
            value = withoutPrefix
        }
    }

    private fun splitVariable(line: String): Pair<String, String>? {
        val partitionScoped = partitionScopedVariable.matchEntire(line)
        if (partitionScoped != null) {
            val family = partitionScoped.groupValues[1].lowercase(Locale.US)
            val target = normalizeName(partitionScoped.groupValues[2])
            val value = partitionScoped.groupValues[3].trim()
            if (target.isBlank() || value.isBlank()) return null
            return "$family:$target" to value
        }

        val separator = line.indexOf(':')
        if (separator <= 0 || separator == line.lastIndex) return null

        val name = normalizeName(line.substring(0, separator))
        val value = line.substring(separator + 1).trim()
        if (name.isBlank() || value.isBlank()) return null
        return name to value
    }

    private fun metadataFieldForKey(key: String): MetadataField? = when {
        key.startsWith("partition-size:") -> MetadataField.SIZE
        key.startsWith("partition-type:") -> MetadataField.TYPE
        key.startsWith("is-logical:") -> MetadataField.LOGICAL
        key.startsWith("has-slot:") -> MetadataField.HAS_SLOT
        else -> null
    }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.US)
}
