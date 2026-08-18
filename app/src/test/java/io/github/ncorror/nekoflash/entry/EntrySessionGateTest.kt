package io.github.ncorror.nekoflash.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntrySessionGateTest {
    @Test
    fun `fresh gate is unauthorized even when current risk schema was acknowledged`() {
        val persistence = FakePersistence(acknowledgedSchemaVersion = 1)
        val gate = EntrySessionGate(persistence)

        assertTrue(gate.isRiskAcknowledged())
        assertFalse(gate.isSessionAuthorized())
    }

    @Test
    fun `unchecked risk cannot authorize or persist`() {
        val persistence = FakePersistence()
        val gate = EntrySessionGate(persistence)

        assertFalse(gate.authorize(userAcceptedRisk = false))
        assertFalse(gate.isSessionAuthorized())
        assertEquals(0, persistence.persistCalls)
    }

    @Test
    fun `first accepted entry persists current schema then authorizes`() {
        val persistence = FakePersistence()
        val gate = EntrySessionGate(persistence)

        assertTrue(gate.authorize(userAcceptedRisk = true))
        assertTrue(gate.isSessionAuthorized())
        assertTrue(gate.isRiskAcknowledged())
        assertEquals(1, persistence.acknowledgedSchemaVersion ?: -1)
        assertEquals(1, persistence.persistCalls)
    }

    @Test
    fun `persistence failure leaves entry unauthorized`() {
        val persistence = FakePersistence(persistSucceeds = false)
        val gate = EntrySessionGate(persistence)

        assertFalse(gate.authorize(userAcceptedRisk = true))
        assertFalse(gate.isSessionAuthorized())
        assertFalse(gate.isRiskAcknowledged())
        assertEquals(1, persistence.persistCalls)
    }

    @Test
    fun `existing acknowledgement authorizes without rewriting preferences`() {
        val persistence = FakePersistence(acknowledgedSchemaVersion = 1)
        val gate = EntrySessionGate(persistence)

        assertTrue(gate.authorize(userAcceptedRisk = true))
        assertTrue(gate.isSessionAuthorized())
        assertEquals(0, persistence.persistCalls)
    }

    @Test
    fun `ending session revokes authorization but keeps risk acknowledgement`() {
        val persistence = FakePersistence()
        val gate = EntrySessionGate(persistence)

        assertTrue(gate.authorize(userAcceptedRisk = true))
        gate.endSession()

        assertFalse(gate.isSessionAuthorized())
        assertTrue(gate.isRiskAcknowledged())
    }

    @Test
    fun `same process can authorize a later entry without rewriting acknowledgement`() {
        val persistence = FakePersistence()
        val gate = EntrySessionGate(persistence)

        assertTrue(gate.authorize(userAcceptedRisk = true))
        gate.endSession()
        assertTrue(gate.authorize(userAcceptedRisk = true))

        assertTrue(gate.isSessionAuthorized())
        assertEquals(1, persistence.persistCalls)
    }

    @Test
    fun `new gate over same persistence starts a new unauthorized process session`() {
        val persistence = FakePersistence()
        val first = EntrySessionGate(persistence)
        assertTrue(first.authorize(userAcceptedRisk = true))

        val second = EntrySessionGate(persistence)

        assertTrue(second.isRiskAcknowledged())
        assertFalse(second.isSessionAuthorized())
    }

    @Test
    fun `schema change requires acknowledgement persistence for the new schema`() {
        val persistence = FakePersistence(acknowledgedSchemaVersion = 1)
        val gate = EntrySessionGate(
            persistence = persistence,
            riskSchemaVersion = 2,
        )

        assertFalse(gate.isRiskAcknowledged())
        assertTrue(gate.authorize(userAcceptedRisk = true))
        assertEquals(2, persistence.acknowledgedSchemaVersion ?: -1)
        assertEquals(1, persistence.persistCalls)
    }

    private class FakePersistence(
        var acknowledgedSchemaVersion: Int? = null,
        private val persistSucceeds: Boolean = true,
    ) : EntrySessionGate.Persistence {
        var persistCalls = 0
            private set

        override fun isRiskAcknowledged(schemaVersion: Int): Boolean =
            acknowledgedSchemaVersion == schemaVersion

        override fun persistRiskAcknowledged(schemaVersion: Int): Boolean {
            persistCalls += 1
            if (!persistSucceeds) return false
            acknowledgedSchemaVersion = schemaVersion
            return true
        }
    }
}
