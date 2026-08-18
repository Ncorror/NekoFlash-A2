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

class UsbSessionLifecyclePolicyTest {
    @Test
    fun `startup scan is scheduled once after legacy delay`() {
        val first = UsbSessionLifecyclePolicy.scheduleStartupScan(alreadyScheduled = false)
        val second = UsbSessionLifecyclePolicy.scheduleStartupScan(
            alreadyScheduled = first.startupScanMarkedScheduled,
        )

        assertTrue(first.startupScanMarkedScheduled)
        assertEquals(350L, first.delayMs)
        assertTrue(second.startupScanMarkedScheduled)
        assertNull(second.delayMs)
    }

    @Test
    fun `startup scan accepts one candidate only in none or error phase`() {
        val single = listOf(device(id = 1))

        assertEquals(
            1,
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.NONE,
                single,
            )?.device?.deviceId,
        )
        assertEquals(
            1,
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.ERROR,
                single,
            )?.device?.deviceId,
        )
        assertNull(
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.CONNECTING,
                single,
            ),
        )
        assertNull(
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.ADB,
                single,
            ),
        )
        assertNull(
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.FASTBOOT,
                single,
            ),
        )
    }

    @Test
    fun `startup scan rejects zero or ambiguous candidates`() {
        assertNull(
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.NONE,
                emptyList(),
            ),
        )
        assertNull(
            UsbSessionLifecyclePolicy.selectStartupCandidate(
                UsbSessionLifecyclePolicy.ConnectionPhase.NONE,
                listOf(device(id = 1, name = "/dev/1"), device(id = 2, name = "/dev/2")),
            ),
        )
    }

    @Test
    fun `attach without device leaves schedulers untouched`() {
        val decision = UsbSessionLifecyclePolicy.onAttached(null)

        assertFalse(decision.cancelStartupScan)
        assertFalse(decision.stopModeSwitchWatch)
        assertNull(decision.candidate)
    }

    @Test
    fun `non-null attach cancels both scans even when descriptor is unrecognized`() {
        val unrecognized = device(
            id = 2,
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = 0x08,
                    interfaceSubclass = 0x06,
                    interfaceProtocol = 0x50,
                    endpoints = bulkEndpoints(),
                ),
            ),
        )

        val decision = UsbSessionLifecyclePolicy.onAttached(unrecognized)

        assertTrue(decision.cancelStartupScan)
        assertTrue(decision.stopModeSwitchWatch)
        assertNull(decision.candidate)
    }

    @Test
    fun `recognized attach selects primary candidate including generic fastboot fallback`() {
        val generic = device(
            id = 3,
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = 0xFF,
                    interfaceSubclass = 0x10,
                    interfaceProtocol = 0x55,
                    endpoints = bulkEndpoints(),
                ),
            ),
        )

        val decision = UsbSessionLifecyclePolicy.onAttached(generic)

        assertTrue(decision.cancelStartupScan)
        assertTrue(decision.stopModeSwitchWatch)
        assertEquals(
            UsbInterfaceSelector.MatchKind.GENERIC_FASTBOOT,
            decision.candidate?.matchKind,
        )
    }

    @Test
    fun `current device match uses device name first or exact id vid pid tuple`() {
        val current = device(id = 10, name = "/dev/current", vendorId = 0x18D1, productId = 1)
        val sameName = device(id = 99, name = "/dev/current", vendorId = 0x9999, productId = 9)
        val sameTuple = device(id = 10, name = "/dev/renamed", vendorId = 0x18D1, productId = 1)
        val unrelated = device(id = 10, name = "/dev/renamed", vendorId = 0x18D1, productId = 2)

        assertTrue(UsbSessionLifecyclePolicy.isCurrentDevice(current, sameName))
        assertTrue(UsbSessionLifecyclePolicy.isCurrentDevice(current, sameTuple))
        assertFalse(UsbSessionLifecyclePolicy.isCurrentDevice(current, unrelated))
    }

    @Test
    fun `current detach disconnects and starts sixteen-attempt same-vendor mode watch`() {
        val current = candidate(device(id = 7, vendorId = 0x2717, mode = Mode.FASTBOOT))

        val decision = UsbSessionLifecyclePolicy.onDetached(current, current.device)

        assertEquals(7, decision.pendingDeviceIdToRemove)
        assertTrue(decision.disconnectCurrent)
        assertEquals(current.logicalSignature, decision.modeSwitchWatch?.previousLogicalSignature)
        assertEquals(0x2717, decision.modeSwitchWatch?.previousVendorId)
        assertEquals(16, decision.modeSwitchWatch?.attemptsRemaining)
    }

    @Test
    fun `unrelated detach removes only its pending id and does not disconnect current`() {
        val current = candidate(device(id = 7, name = "/dev/current"))
        val unrelated = device(id = 9, name = "/dev/other")

        val decision = UsbSessionLifecyclePolicy.onDetached(current, unrelated)

        assertEquals(9, decision.pendingDeviceIdToRemove)
        assertFalse(decision.disconnectCurrent)
        assertNull(decision.modeSwitchWatch)
    }

    @Test
    fun `mode switch accepts one changed logical profile from previous vendor`() {
        val previous = candidate(device(id = 1, vendorId = 0x18D1, mode = Mode.ADB))
        val watch = UsbSessionLifecyclePolicy.ModeSwitchWatch(
            previousLogicalSignature = previous.logicalSignature,
            previousVendorId = previous.device.vendorId,
        )
        val fastboot = device(id = 2, name = "/dev/new", vendorId = 0x18D1, mode = Mode.FASTBOOT)

        val result = UsbSessionLifecyclePolicy.tickModeSwitch(watch, listOf(fastboot))

        assertEquals(Mode.FASTBOOT, result.candidate?.mode)
        assertNull(result.nextWatch)
        assertNull(result.scheduleNextAfterMs)
    }

    @Test
    fun `same logical profile and other vendor are ignored and consume one attempt`() {
        val previousDevice = device(id = 1, vendorId = 0x18D1, mode = Mode.ADB)
        val previous = candidate(previousDevice)
        val sameProfile = previousDevice.copy(deviceId = 101, deviceName = "/dev/same")
        val otherVendor = device(id = 3, name = "/dev/other", vendorId = 0x2717, mode = Mode.FASTBOOT)
        val watch = UsbSessionLifecyclePolicy.ModeSwitchWatch(
            previousLogicalSignature = previous.logicalSignature,
            previousVendorId = previous.device.vendorId,
        )

        val result = UsbSessionLifecyclePolicy.tickModeSwitch(
            watch,
            listOf(sameProfile, otherVendor),
        )

        assertNull(result.candidate)
        assertEquals(15, result.nextWatch?.attemptsRemaining)
        assertEquals(750L, result.scheduleNextAfterMs)
    }

    @Test
    fun `ambiguous changed candidates do not auto-connect`() {
        val previous = candidate(device(id = 1, vendorId = 0x18D1, mode = Mode.ADB))
        val watch = UsbSessionLifecyclePolicy.ModeSwitchWatch(
            previousLogicalSignature = previous.logicalSignature,
            previousVendorId = previous.device.vendorId,
        )
        val first = device(id = 2, name = "/dev/fastboot-1", vendorId = 0x18D1, mode = Mode.FASTBOOT)
        val second = device(id = 3, name = "/dev/fastboot-2", vendorId = 0x18D1, mode = Mode.FASTBOOT)

        val result = UsbSessionLifecyclePolicy.tickModeSwitch(watch, listOf(first, second))

        assertNull(result.candidate)
        assertEquals(15, result.nextWatch?.attemptsRemaining)
        assertEquals(750L, result.scheduleNextAfterMs)
    }

    @Test
    fun `mode switch watch performs exactly sixteen misses then stops`() {
        var watch: UsbSessionLifecyclePolicy.ModeSwitchWatch? =
            UsbSessionLifecyclePolicy.ModeSwitchWatch(
                previousLogicalSignature = "old",
                previousVendorId = 0x18D1,
            )
        var ticks = 0

        while (watch != null) {
            val result = UsbSessionLifecyclePolicy.tickModeSwitch(watch, emptyList())
            ticks += 1
            watch = result.nextWatch
            if (watch != null) assertEquals(750L, result.scheduleNextAfterMs)
        }

        assertEquals(16, ticks)
    }

    private fun candidate(device: UsbDeviceDescriptor): UsbInterfaceSelector.Candidate =
        UsbInterfaceSelector.selectPrimaryCandidate(device)!!

    private fun device(
        id: Int,
        name: String = "/dev/usb/$id",
        vendorId: Int = 0x18D1,
        productId: Int = 0x4EE7,
        mode: Mode = Mode.ADB,
        interfaces: List<UsbInterfaceDescriptor>? = null,
    ): UsbDeviceDescriptor = UsbDeviceDescriptor(
        deviceId = id,
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        productName = "target-$id",
        interfaces = interfaces ?: listOf(
            UsbInterfaceDescriptor(
                id = 0,
                interfaceClass = 0xFF,
                interfaceSubclass = 0x42,
                interfaceProtocol = if (mode == Mode.ADB) 0x01 else 0x03,
                endpoints = bulkEndpoints(),
            ),
        ),
    )

    private fun bulkEndpoints(): List<UsbEndpointDescriptor> = listOf(
        UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
        UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
    )

}
