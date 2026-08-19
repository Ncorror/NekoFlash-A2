package io.github.ncorror.nekoflash.usb.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionObservationStoreTest {
    @Test
    fun `replacement listener receives current snapshot immediately`() {
        val ready = UsbSessionObservation(status = UsbSessionObservation.Status.CANDIDATE_READY)
        val store = UsbSessionObservationStore(initial = ready)
        val received = mutableListOf<UsbSessionObservation>()

        store.replaceListener(received::add)

        assertEquals(listOf(ready), received)
    }

    @Test
    fun `replacement listener receives latest snapshot rather than reconstructing state`() {
        val store = UsbSessionObservationStore()
        val ready = UsbSessionObservation(status = UsbSessionObservation.Status.CANDIDATE_READY)
        store.publish(ready)
        val received = mutableListOf<UsbSessionObservation>()

        store.replaceListener(received::add)

        assertEquals(listOf(ready), received)
    }

    @Test
    fun `old activity generation cannot clear replacement listener`() {
        val store = UsbSessionObservationStore()
        val first = mutableListOf<UsbSessionObservation>()
        val second = mutableListOf<UsbSessionObservation>()
        val firstGeneration = store.replaceListener(first::add)
        store.replaceListener(second::add)

        store.clearListener(firstGeneration)
        val scanning = UsbSessionObservation(status = UsbSessionObservation.Status.SCANNING)
        store.publish(scanning)

        assertEquals(1, first.size)
        assertEquals(scanning, second.last())
    }

    @Test
    fun `current activity generation can clear listener without losing stored observation`() {
        val store = UsbSessionObservationStore()
        val received = mutableListOf<UsbSessionObservation>()
        val generation = store.replaceListener(received::add)
        store.clearListener(generation)

        val denied = UsbSessionObservation(status = UsbSessionObservation.Status.PERMISSION_DENIED)
        store.publish(denied)

        assertEquals(1, received.size)
        assertEquals(denied, store.current())
        assertTrue(received.none { it == denied })
    }
}
