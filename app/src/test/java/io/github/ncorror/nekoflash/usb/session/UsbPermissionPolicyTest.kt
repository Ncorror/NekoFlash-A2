package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Mode
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbTransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionPolicyTest {
    @Test
    fun `already granted permission connects immediately and leaves no pending request`() {
        val candidate = candidate(device())

        val result = UsbPermissionPolicy.beginAccess(
            registry = UsbPermissionPolicy.Registry(),
            candidate = candidate,
            automatic = false,
            permissionAlreadyGranted = true,
        )

        assertEquals(0, result.registry.size)
        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(candidate, action.candidate)
        assertFalse(action.automatic)
    }

    @Test
    fun `missing permission tracks request and uses legacy thirty second timeout`() {
        val candidate = candidate(device())

        val result = UsbPermissionPolicy.beginAccess(
            registry = UsbPermissionPolicy.Registry(),
            candidate = candidate,
            automatic = true,
            permissionAlreadyGranted = false,
        )

        assertEquals(1, result.registry.size)
        assertEquals(candidate, result.registry.requestForDeviceId(candidate.device.deviceId)?.candidate)
        val action = result.action as UsbPermissionPolicy.Action.RequestPermission
        assertEquals(30_000L, action.timeoutMs)
        assertTrue(action.automatic)
    }

    @Test
    fun `exact callback device id wins over an earlier same-name pending request`() {
        val first = candidate(device(id = 10, name = "/dev/usb/x"))
        val second = candidate(device(id = 20, name = "/dev/usb/x"))
        var registry = UsbPermissionPolicy.Registry()
        registry = beginPending(registry, first, automatic = false)
        registry = beginPending(registry, second, automatic = true)

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = second.device,
            granted = true,
        )

        assertEquals(1, result.registry.size)
        assertEquals(first, result.registry.requestForDeviceId(10)?.candidate)
        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(20, action.candidate.device.deviceId)
        assertTrue(action.automatic)
        assertEquals(20, result.timeoutDeviceIdToCancel)
    }

    @Test
    fun `device-name fallback takes first pending request in insertion order`() {
        val first = candidate(device(id = 10, name = "/dev/usb/shared"))
        val second = candidate(device(id = 20, name = "/dev/usb/shared"))
        var registry = UsbPermissionPolicy.Registry()
        registry = beginPending(registry, first, automatic = false)
        registry = beginPending(registry, second, automatic = true)
        val callbackDevice = device(id = 99, name = "/dev/usb/shared")

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = callbackDevice,
            granted = true,
        )

        assertEquals(1, result.registry.size)
        assertNull(result.registry.requestForDeviceId(10))
        assertEquals(second, result.registry.requestForDeviceId(20)?.candidate)
        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(99, action.candidate.device.deviceId)
        assertFalse(action.automatic)
    }

    @Test
    fun `replacing same device id keeps its original fallback position`() {
        val first = candidate(device(id = 10, name = "/dev/usb/shared"))
        val second = candidate(device(id = 20, name = "/dev/usb/shared"))
        val replacement = candidate(
            device(
                id = 10,
                name = "/dev/usb/shared",
                productId = 0x4EE8,
            ),
        )
        var registry = UsbPermissionPolicy.Registry()
        registry = beginPending(registry, first, automatic = false)
        registry = beginPending(registry, second, automatic = false)
        registry = beginPending(registry, replacement, automatic = true)

        val callback = device(id = 99, name = "/dev/usb/shared", productId = 0x4EE8)
        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = callback,
            granted = true,
        )

        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertTrue(action.automatic)
        assertEquals(0x4EE8, action.candidate.device.productId)
        assertEquals(1, result.registry.size)
        assertEquals(second, result.registry.requestForDeviceId(20)?.candidate)
    }

    @Test
    fun `name fallback rebinds to callback descriptor but cancels only callback-id timeout`() {
        val original = candidate(device(id = 7, name = "/dev/usb/oem"))
        val registry = beginPending(
            UsbPermissionPolicy.Registry(),
            original,
            automatic = false,
        )
        val refreshed = device(id = 42, name = "/dev/usb/oem")

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = refreshed,
            granted = true,
        )

        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(42, action.candidate.device.deviceId)
        assertFalse(action.automatic)
        assertEquals(42, result.timeoutDeviceIdToCancel)
        assertEquals(0, result.registry.size)
    }

    @Test
    fun `failed rebind falls back to current primary interface and preserves pending automatic flag`() {
        val adb = candidate(device(id = 1, name = "/dev/usb/switch", mode = Mode.ADB))
        val registry = beginPending(
            UsbPermissionPolicy.Registry(),
            adb,
            automatic = false,
        )
        val refreshedFastboot = device(id = 1, name = "/dev/usb/switch", mode = Mode.FASTBOOT)

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = refreshedFastboot,
            granted = true,
        )

        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(Mode.FASTBOOT, action.candidate.mode)
        assertFalse(action.automatic)
    }

    @Test
    fun `permission denial removes matching pending request and never connects`() {
        val candidate = candidate(device(id = 3))
        val registry = beginPending(
            UsbPermissionPolicy.Registry(),
            candidate,
            automatic = true,
        )

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = candidate.device,
            granted = false,
        )

        assertEquals(UsbPermissionPolicy.Action.PermissionDenied, result.action)
        assertEquals(0, result.registry.size)
        assertEquals(3, result.timeoutDeviceIdToCancel)
    }

    @Test
    fun `permission result without device preserves registry`() {
        val candidate = candidate(device(id = 4))
        val registry = beginPending(
            UsbPermissionPolicy.Registry(),
            candidate,
            automatic = true,
        )

        val denied = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = null,
            granted = false,
        )
        val granted = UsbPermissionPolicy.resolvePermissionResult(
            registry = registry,
            device = null,
            granted = true,
        )

        assertEquals(1, denied.registry.size)
        assertEquals(UsbPermissionPolicy.Action.PermissionDenied, denied.action)
        assertNull(denied.timeoutDeviceIdToCancel)
        assertEquals(1, granted.registry.size)
        assertEquals(UsbPermissionPolicy.Action.MissingDevice, granted.action)
        assertNull(granted.timeoutDeviceIdToCancel)
    }

    @Test
    fun `granted callback without pending request auto-connects recognized device`() {
        val callbackDevice = device(id = 55)

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = UsbPermissionPolicy.Registry(),
            device = callbackDevice,
            granted = true,
        )

        val action = result.action as UsbPermissionPolicy.Action.Connect
        assertEquals(55, action.candidate.device.deviceId)
        assertTrue(action.automatic)
    }

    @Test
    fun `granted unrecognized callback without pending request does not connect`() {
        val unrecognized = UsbDeviceDescriptor(
            deviceId = 77,
            deviceName = "/dev/usb/unrecognized",
            vendorId = 0x18D1,
            productId = 0x9999,
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = 0x08,
                    interfaceSubclass = 0x06,
                    interfaceProtocol = 0x50,
                    endpoints = listOf(
                        UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
                        UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
                    ),
                ),
            ),
        )

        val result = UsbPermissionPolicy.resolvePermissionResult(
            registry = UsbPermissionPolicy.Registry(),
            device = unrecognized,
            granted = true,
        )

        assertEquals(UsbPermissionPolicy.Action.NoCandidate, result.action)
        assertEquals(0, result.registry.size)
        assertEquals(77, result.timeoutDeviceIdToCancel)
    }

    @Test
    fun `timeout removes exact request and only reports when permission is still absent`() {
        val first = candidate(device(id = 1, name = "/dev/usb/1"))
        val second = candidate(device(id = 2, name = "/dev/usb/2"))
        var registry = UsbPermissionPolicy.Registry()
        registry = beginPending(registry, first, automatic = true)
        registry = beginPending(registry, second, automatic = true)

        val missing = UsbPermissionPolicy.onTimeout(
            registry = registry,
            requestedDeviceId = 1,
            permissionGrantedNow = false,
        )
        assertTrue(missing.shouldReportNoResponse)
        assertNull(missing.registry.requestForDeviceId(1))
        assertEquals(second, missing.registry.requestForDeviceId(2)?.candidate)

        val silentlyGranted = UsbPermissionPolicy.onTimeout(
            registry = registry,
            requestedDeviceId = 1,
            permissionGrantedNow = true,
        )
        assertFalse(silentlyGranted.shouldReportNoResponse)
        assertNull(silentlyGranted.registry.requestForDeviceId(1))
    }

    private fun beginPending(
        registry: UsbPermissionPolicy.Registry,
        candidate: UsbInterfaceSelector.Candidate,
        automatic: Boolean,
    ): UsbPermissionPolicy.Registry = UsbPermissionPolicy.beginAccess(
        registry = registry,
        candidate = candidate,
        automatic = automatic,
        permissionAlreadyGranted = false,
    ).registry

    private fun candidate(device: UsbDeviceDescriptor): UsbInterfaceSelector.Candidate =
        UsbInterfaceSelector.selectPrimaryCandidate(device)!!

    private fun device(
        id: Int = 1,
        name: String = "/dev/usb/1",
        vendorId: Int = 0x18D1,
        productId: Int = 0x4EE7,
        mode: Mode = Mode.ADB,
    ): UsbDeviceDescriptor = UsbDeviceDescriptor(
        deviceId = id,
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        productName = "target-$id",
        interfaces = listOf(
            UsbInterfaceDescriptor(
                id = 0,
                interfaceClass = 0xFF,
                interfaceSubclass = 0x42,
                interfaceProtocol = if (mode == Mode.ADB) 0x01 else 0x03,
                endpoints = listOf(
                    UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
                    UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
                ),
            ),
        ),
    )
}
