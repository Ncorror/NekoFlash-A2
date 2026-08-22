package io.github.ncorror.nekoflash.fastboot.transport

/**
 * Pure Fastboot response state used by the first read-only A2 Fastboot slice.
 *
 * The legacy peer qualification sends exactly one `getvar:product`. A protocol-level
 * `FAIL` still proves that the selected bulk interface speaks Fastboot; transport
 * timeouts/short writes do not.
 */
internal class FastbootReadOnlySession(
    private val variableName: String = PRODUCT_VARIABLE,
) {
    data class Packet(
        val type: String,
        val payload: String,
        val raw: String,
    )

    sealed interface Decision {
        data object Continue : Decision

        data class Qualified(
            val product: String?,
            val finalType: String,
            val finalPayload: String,
        ) : Decision
    }

    private var latestInfoValue: String? = null

    fun accept(packet: Packet): Decision = when (packet.type) {
        "INFO", "TEXT" -> {
            normalizeGetVarValue(variableName, packet.payload)?.let { latestInfoValue = it }
            Decision.Continue
        }

        "OKAY" -> {
            val direct = packet.payload
                .takeIf { it.isNotBlank() }
                ?.let { normalizeGetVarValue(variableName, it) }
            Decision.Qualified(
                product = direct ?: latestInfoValue,
                finalType = packet.type,
                finalPayload = packet.payload,
            )
        }

        "FAIL" -> Decision.Qualified(
            product = null,
            finalType = packet.type,
            finalPayload = packet.payload,
        )

        else -> Decision.Continue
    }

    companion object {
        const val PRODUCT_VARIABLE = "product"
        const val PRODUCT_COMMAND = "getvar:$PRODUCT_VARIABLE"

        fun parsePacket(bytes: ByteArray, length: Int = bytes.size): Packet {
            require(length in 0..bytes.size) { "length is outside byte array" }
            val raw = String(bytes, 0, length, Charsets.US_ASCII)
                .replace("\u0000", "")
                .trim()
            if (raw.length < 4) return Packet(type = "UNKNOWN", payload = raw, raw = raw)
            return Packet(
                type = raw.take(4),
                payload = raw.drop(4).trim(),
                raw = raw,
            )
        }

        fun normalizeGetVarValue(name: String, raw: String): String? {
            val cleaned = raw.trim().removePrefix("INFO").trim()
            val variants = listOf(name, name.replace('-', '_'))
            variants.forEach { variant ->
                val prefix = "$variant:"
                if (cleaned.startsWith(prefix, ignoreCase = true)) {
                    return cleaned.substring(prefix.length).trim().ifBlank { null }
                }
            }
            return cleaned.substringAfter(':', cleaned).trim().ifBlank { null }
        }
    }
}

/** Legacy hardware-proven timing window for the first Fastboot getvar qualification. */
internal object FastbootReadOnlyTiming {
    const val HANDSHAKE_SETTLE_MS = 350L
    const val HANDSHAKE_TIMEOUT_MS = 7_000
    const val READ_SLICE_MS = 900
    const val MAX_FAILED_READS = 3
    const val READ_RETRY_DELAY_MS = 100L
    const val MIN_PATIENCE_MS = 1_500L

    fun nextReadTimeoutMs(remainingMs: Long): Int =
        minOf(READ_SLICE_MS.toLong(), remainingMs.coerceAtLeast(1L)).toInt()

    fun shouldFailAfterEmptyRead(emptyReads: Int, elapsedMs: Long): Boolean =
        emptyReads >= MAX_FAILED_READS && elapsedMs >= MIN_PATIENCE_MS
}
