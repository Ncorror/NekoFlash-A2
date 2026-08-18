package io.github.ncorror.nekoflash.usb.diagnostics

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbTransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionSnapshotTest {
    @Test
    fun `capture keeps selected candidate and all descriptor evidence`() {
        val candidate = UsbInterfaceSelector.selectPrimaryCandidate(device())!!

        val snapshot = UsbSessionSnapshot.capture(
            sessionId = "session-1",
            capturedAtEpochMs = 1234L,
            candidate = candidate,
            host = host(),
        )

        assertEquals("ADB", snapshot.mode)
        assertEquals("CANONICAL", snapshot.matchKind)
        assertEquals(1, snapshot.device.interfaces.size)
        assertEquals(0, snapshot.selectedInterfaceIndex)
        assertEquals(0x81, snapshot.selectedEndpointInAddress)
        assertEquals(0x01, snapshot.selectedEndpointOutAddress)
    }

    @Test
    fun `json uses A2 schema identity`() {
        val json = snapshot().toJson()

        assertTrue(json.contains("\"schema\":\"io.github.ncorror.nekoflash.usb-session.v1\""))
    }

    @Test
    fun `json escapes names and contains endpoint inventory`() {
        val json = snapshot(deviceName = "/dev/usb/\"quoted\"", productName = "line\nname").toJson()

        assertTrue(json.contains("/dev/usb/\\\"quoted\\\""))
        assertTrue(json.contains("line\\nname"))
        assertTrue(json.contains("\"address\":129"))
        assertTrue(json.contains("\"direction\":\"IN\""))
        assertTrue(json.contains("\"transferType\":\"BULK\""))
    }

    @Test
    fun `text report includes selected endpoints and host evidence`() {
        val text = snapshot().toDiagnosticText()

        assertTrue(text.contains("selectedEndpointIn=0x81"))
        assertTrue(text.contains("selectedEndpointOut=0x01"))
        assertTrue(text.contains("interface[0].endpoint[0]=address=0x81"))
        assertTrue(text.contains("hostSdk=34"))
        assertTrue(text.contains("hostModel=host-model"))
    }

    private fun snapshot(
        deviceName: String = "/dev/usb/1",
        productName: String = "target",
    ): UsbSessionSnapshot {
        val candidate = UsbInterfaceSelector.selectPrimaryCandidate(
            device(deviceName = deviceName, productName = productName),
        )!!
        return UsbSessionSnapshot.capture(
            sessionId = "session-1",
            capturedAtEpochMs = 1234L,
            candidate = candidate,
            host = host(),
        )
    }

    private fun host() = UsbSessionSnapshot.HostSnapshot(
        sdkInt = 34,
        release = "14",
        manufacturer = "host-maker",
        model = "host-model",
        device = "host-device",
    )

    private fun device(
        deviceName: String = "/dev/usb/1",
        productName: String = "target",
    ) = UsbDeviceDescriptor(
        deviceId = 7,
        deviceName = deviceName,
        vendorId = 0x18D1,
        productId = 0x4EE7,
        productName = productName,
        interfaces = listOf(
            UsbInterfaceDescriptor(
                id = 9,
                interfaceClass = 0xFF,
                interfaceSubclass = 0x42,
                interfaceProtocol = 0x01,
                endpoints = listOf(
                    UsbEndpointDescriptor(
                        address = 0x81,
                        direction = UsbEndpointDirection.IN,
                        transferType = UsbTransferType.BULK,
                        maxPacketSize = 512,
                    ),
                    UsbEndpointDescriptor(
                        address = 0x01,
                        direction = UsbEndpointDirection.OUT,
                        transferType = UsbTransferType.BULK,
                        maxPacketSize = 512,
                    ),
                ),
            ),
        ),
    )
}
