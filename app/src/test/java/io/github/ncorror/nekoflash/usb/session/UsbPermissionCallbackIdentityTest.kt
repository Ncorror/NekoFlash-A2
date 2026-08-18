package io.github.ncorror.nekoflash.usb.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbPermissionCallbackIdentityTest {
    @Test
    fun `first callback uses generation one and all identity fields in action`() {
        val identity = UsbPermissionCallbackIdentity(
            actionPrefix = "io.github.ncorror.nekoflash.USB_PERMISSION",
            processToken = "process-a",
        )

        val callback = identity.nextCallback()

        assertEquals(1L, callback.generation)
        assertEquals(
            "io.github.ncorror.nekoflash.USB_PERMISSION.process-a.1",
            callback.action,
        )
    }

    @Test
    fun `successive activations cannot share one callback action`() {
        val identity = UsbPermissionCallbackIdentity("pkg.USB_PERMISSION", "process-a")

        val first = identity.nextCallback()
        val second = identity.nextCallback()

        assertEquals(2L, second.generation)
        assertNotEquals(first.action, second.action)
    }

    @Test
    fun `new process token cannot reproduce old process callback action`() {
        val oldProcess = UsbPermissionCallbackIdentity("pkg.USB_PERMISSION", "process-a")
        val newProcess = UsbPermissionCallbackIdentity("pkg.USB_PERMISSION", "process-b")

        assertNotEquals(
            oldProcess.nextCallback().action,
            newProcess.nextCallback().action,
        )
    }

    @Test
    fun `blank identity components are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbPermissionCallbackIdentity("", "process-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbPermissionCallbackIdentity("pkg.USB_PERMISSION", "")
        }
    }
}
