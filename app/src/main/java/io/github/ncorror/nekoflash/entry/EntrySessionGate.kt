package io.github.ncorror.nekoflash.entry

/**
 * Process-scoped authorization gate for entering NekoFlash's device-operation UI.
 *
 * Risk acknowledgement may persist across launches, but authorization never does.
 * A fresh process therefore starts unauthorized even when the current risk schema
 * was acknowledged previously.
 */
class EntrySessionGate(
    private val persistence: Persistence,
    private val riskSchemaVersion: Int = CURRENT_RISK_SCHEMA_VERSION,
) {
    interface Persistence {
        fun isRiskAcknowledged(schemaVersion: Int): Boolean

        /** Returns true only when the acknowledgement was durably persisted. */
        fun persistRiskAcknowledged(schemaVersion: Int): Boolean
    }

    @Volatile
    private var sessionAuthorized = false

    fun isSessionAuthorized(): Boolean = sessionAuthorized

    fun isRiskAcknowledged(): Boolean =
        persistence.isRiskAcknowledged(riskSchemaVersion)

    /**
     * The user must actively keep the risk acknowledgement selected for this entry.
     * Existing persisted acknowledgement avoids another write, but never bypasses the
     * per-process session authorization step.
     */
    fun authorize(userAcceptedRisk: Boolean): Boolean {
        if (!userAcceptedRisk) return false
        if (!isRiskAcknowledged() && !persistence.persistRiskAcknowledged(riskSchemaVersion)) {
            return false
        }
        sessionAuthorized = true
        return true
    }

    /** Ends only volatile entry authorization; persisted risk acknowledgement remains. */
    fun endSession() {
        sessionAuthorized = false
    }

    companion object {
        const val CURRENT_RISK_SCHEMA_VERSION = 1
    }
}
