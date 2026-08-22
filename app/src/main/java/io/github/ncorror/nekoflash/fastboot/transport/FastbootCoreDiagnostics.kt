package io.github.ncorror.nekoflash.fastboot.transport

/**
 * Fixed read-only Fastboot diagnostic plan migrated from legacy refreshDiagnostics().
 *
 * The plan is intentionally closed: callers cannot inject arbitrary variable names. The
 * first A2 diagnostic expansion reads only the four values required to establish slot,
 * unlock, and transfer-limit facts before any mutation path is considered.
 */
internal object FastbootCoreDiagnosticsPlan {
    data class Variable(
        val name: String,
        val timeoutMs: Int = GETVAR_TIMEOUT_MS,
        val sensitive: Boolean = false,
    ) {
        val command: String = "getvar:$name"

        fun valueForEvent(value: String?): String? = when {
            value == null -> null
            sensitive -> "<redacted>"
            else -> value
        }

        fun payloadForEvent(payload: String): String = when {
            sensitive && payload.isNotBlank() -> "<redacted>"
            else -> payload
        }
    }

    val variables: List<Variable> = listOf(
        Variable("current-slot"),
        Variable("slot-count"),
        Variable("unlocked"),
        Variable("max-download-size"),
    )

    fun parseFastbootSize(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val token = Regex("0x[0-9A-Fa-f]+|[0-9]+").find(raw)?.value ?: return null
        return try {
            if (token.startsWith("0x", ignoreCase = true)) {
                token.removePrefix("0x").removePrefix("0X").toLong(16)
            } else {
                token.toLong()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    const val GETVAR_TIMEOUT_MS = 5_000
}

internal data class FastbootCoreDiagnostics(
    val currentSlot: String? = null,
    val slotCount: String? = null,
    val unlocked: String? = null,
    val maxDownloadSizeRaw: String? = null,
    val maxDownloadSizeBytes: Long? = null,
)

/** Pure per-getvar response collector mirroring legacy readGetVarResponse(). */
internal class FastbootReadOnlyGetVarSession(
    private val variableName: String,
) {
    sealed interface Decision {
        data object Continue : Decision

        data class Complete(
            val value: String?,
            val finalType: String,
            val finalPayload: String,
        ) : Decision
    }

    private var latestInfoValue: String? = null

    fun accept(packet: FastbootReadOnlySession.Packet): Decision = when (packet.type) {
        "INFO", "TEXT" -> {
            FastbootReadOnlySession.normalizeGetVarValue(variableName, packet.payload)
                ?.let { latestInfoValue = it }
            Decision.Continue
        }

        "OKAY" -> {
            val direct = packet.payload
                .takeIf { it.isNotBlank() }
                ?.let { FastbootReadOnlySession.normalizeGetVarValue(variableName, it) }
            Decision.Complete(
                value = direct ?: latestInfoValue,
                finalType = packet.type,
                finalPayload = packet.payload,
            )
        }

        "FAIL" -> Decision.Complete(
            value = null,
            finalType = packet.type,
            finalPayload = packet.payload,
        )

        else -> Decision.Continue
    }
}
