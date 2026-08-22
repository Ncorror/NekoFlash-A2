package io.github.ncorror.nekoflash.fastboot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootExtendedDiagnosticsTest {
    @Test
    fun `extended diagnostic command surface is fixed and read only`() {
        assertEquals(
            listOf(
                "getvar:slot-suffix",
                "getvar:secure",
                "getvar:serialno",
                "getvar:version-bootloader",
                "getvar:anti",
                "getvar:is-userspace",
                "getvar:super-partition-name",
                "getvar:snapshot-update-status",
                "getvar:max-fetch-size",
            ),
            FastbootExtendedDiagnosticsPlan.fixedCommandOrderWithoutFallback,
        )
    }

    @Test
    fun `extended getvars keep legacy point query timeout`() {
        val variables = FastbootExtendedDiagnosticsPlan.beforeAnti +
            FastbootExtendedDiagnosticsPlan.antiPrimary +
            FastbootExtendedDiagnosticsPlan.afterAnti +
            FastbootExtendedDiagnosticsPlan.antiFallback
        assertTrue(variables.all { it.timeoutMs == FastbootCoreDiagnosticsPlan.GETVAR_TIMEOUT_MS })
    }

    @Test
    fun `antirollback fallback is used only when anti is unreported`() {
        assertTrue(FastbootExtendedDiagnosticsPlan.shouldQueryAntiRollback(null))
        assertTrue(FastbootExtendedDiagnosticsPlan.shouldQueryAntiRollback("  "))
        assertFalse(FastbootExtendedDiagnosticsPlan.shouldQueryAntiRollback("1"))
    }

    @Test
    fun `antirollback fallback command remains fixed`() {
        assertEquals("getvar:antirollback", FastbootExtendedDiagnosticsPlan.antiFallback.command)
    }

    @Test
    fun `serial query is marked sensitive`() {
        val serial = FastbootExtendedDiagnosticsPlan.beforeAnti.single { it.name == "serialno" }
        assertTrue(serial.sensitive)
    }

    @Test
    fun `sensitive serial value is redacted from diagnostics events`() {
        val serial = FastbootExtendedDiagnosticsPlan.beforeAnti.single { it.name == "serialno" }
        assertEquals("<redacted>", serial.valueForEvent("ABC123"))
        assertEquals("<redacted>", serial.payloadForEvent("serialno: ABC123"))
    }

    @Test
    fun `non sensitive values remain observable`() {
        val secure = FastbootExtendedDiagnosticsPlan.beforeAnti.single { it.name == "secure" }
        assertEquals("yes", secure.valueForEvent("yes"))
        assertEquals("secure: yes", secure.payloadForEvent("secure: yes"))
    }

    @Test
    fun `max fetch size reuses legacy fastboot size parser`() {
        assertEquals(
            805_306_368L,
            FastbootCoreDiagnosticsPlan.parseFastbootSize("805306368"),
        )
        assertEquals(
            0x30000000L,
            FastbootCoreDiagnosticsPlan.parseFastbootSize("0x30000000"),
        )
    }
}
