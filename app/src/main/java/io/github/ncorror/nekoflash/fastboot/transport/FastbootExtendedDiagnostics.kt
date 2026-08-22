package io.github.ncorror.nekoflash.fastboot.transport

/**
 * Remaining fixed read-only values from legacy FastbootProtocol.refreshDiagnostics().
 *
 * A2 appends this set after the already hardware-proven Stage 6C2 prefix rather than
 * reordering that prefix. Wire semantics, per-query timeout, FAIL handling, and the
 * `anti` -> `antirollback` fallback remain legacy-faithful.
 */
internal object FastbootExtendedDiagnosticsPlan {
    val beforeAnti: List<FastbootCoreDiagnosticsPlan.Variable> = listOf(
        FastbootCoreDiagnosticsPlan.Variable("slot-suffix"),
        FastbootCoreDiagnosticsPlan.Variable("secure"),
        FastbootCoreDiagnosticsPlan.Variable("serialno", sensitive = true),
        FastbootCoreDiagnosticsPlan.Variable("version-bootloader"),
    )

    val antiPrimary = FastbootCoreDiagnosticsPlan.Variable("anti")
    val antiFallback = FastbootCoreDiagnosticsPlan.Variable("antirollback")

    val afterAnti: List<FastbootCoreDiagnosticsPlan.Variable> = listOf(
        FastbootCoreDiagnosticsPlan.Variable("is-userspace"),
        FastbootCoreDiagnosticsPlan.Variable("super-partition-name"),
        FastbootCoreDiagnosticsPlan.Variable("snapshot-update-status"),
        FastbootCoreDiagnosticsPlan.Variable("max-fetch-size"),
    )

    val fixedCommandOrderWithoutFallback: List<String>
        get() = (beforeAnti + antiPrimary + afterAnti).map { it.command }

    fun shouldQueryAntiRollback(primaryValue: String?): Boolean = primaryValue.isNullOrBlank()
}

internal data class FastbootExtendedDiagnostics(
    val slotSuffix: String? = null,
    val secure: String? = null,
    val serialReported: Boolean = false,
    val versionBootloader: String? = null,
    val antiRollback: String? = null,
    val antiRollbackSource: String? = null,
    val isUserspace: String? = null,
    val superPartitionName: String? = null,
    val snapshotUpdateStatus: String? = null,
    val maxFetchSizeRaw: String? = null,
    val maxFetchSizeBytes: Long? = null,
)
