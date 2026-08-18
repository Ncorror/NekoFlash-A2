package io.github.ncorror.nekoflash.entry

import android.annotation.SuppressLint
import android.content.Context

class SharedPreferencesEntrySessionPersistence(context: Context) : EntrySessionGate.Persistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isRiskAcknowledged(schemaVersion: Int): Boolean =
        preferences.getInt(KEY_RISK_SCHEMA_VERSION, 0) == schemaVersion

    /** The gate needs the durable result before it can authorize this entry. */
    @SuppressLint("ApplySharedPref")
    @Suppress("UseKtx")
    override fun persistRiskAcknowledged(schemaVersion: Int): Boolean =
        preferences.edit()
            .putInt(KEY_RISK_SCHEMA_VERSION, schemaVersion)
            .commit()

    private companion object {
        const val PREFS_NAME = "nekoflash_entry_session"
        const val KEY_RISK_SCHEMA_VERSION = "risk_schema_version"
    }
}
