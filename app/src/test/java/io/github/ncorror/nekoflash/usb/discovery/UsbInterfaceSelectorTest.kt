package io.github.ncorror.nekoflash.usb.discovery

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.MatchKind
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

class UsbInterfaceSelectorTest {
    @Test
    fun `canonical adb suppresses neighboring fastboot-like vendor interfaces`() {
        val device = device(
            interfaces = listOf(
                usbInterface(subclass = 0x10, protocol = 0x03),
                adbInterface(),
                fastbootInterface(),
            ),
        )

        val candidates = UsbInterfaceSelector.findCandidates(device)

        assertEquals(1, candidates.size)
        assertEquals(Mode.ADB, candidates.single().mode)
        assertEquals(MatchKind.CANONICAL, candidates.single().matchKind)
        assertEquals(1, candidates.single().interfaceIndex)
    }

    @Test
    fun `canonical fastboot wins when canonical adb is absent`() {
        val device = device(
            interfaces = listOf(
                usbInterface(subclass = 0x42, protocol = 0x7F),
                fastbootInterface(),
            ),
        )

        val candidate = UsbInterfaceSelector.selectPrimaryCandidate(device)!!

        assertEquals(Mode.FASTBOOT, candidate.mode)
        assertEquals(MatchKind.CANONICAL, candidate.matchKind)
        assertEquals(1, candidate.interfaceIndex)
    }

    @Test
    fun `android compatible fastboot is used before generic vendor fallback`() {
        val device = device(
            interfaces = listOf(
                usbInterface(subclass = 0x10, protocol = 0x55),
                usbInterface(subclass = 0x42, protocol = 0x55),
            ),
        )

        val candidates = UsbInterfaceSelector.findCandidates(device)

        assertEquals(1, candidates.size)
        assertEquals(MatchKind.COMPAT_FASTBOOT, candidates.single().matchKind)
        assertEquals(1, candidates.single().interfaceIndex)
    }

    @Test
    fun `generic vendor fallback is optional and never accepts adb protocol`() {
        val generic = device(
            interfaces = listOf(usbInterface(subclass = 0x10, protocol = 0x55)),
        )
        val vendorAdbProtocol = device(
            name = "/dev/bus/usb/001/003",
            interfaces = listOf(usbInterface(subclass = 0x10, protocol = 0x01)),
        )

        assertEquals(MatchKind.GENERIC_FASTBOOT, UsbInterfaceSelector.findCandidates(generic).single().matchKind)
        assertTrue(UsbInterfaceSelector.findCandidates(generic, includeGenericFastboot = false).isEmpty())
        assertTrue(UsbInterfaceSelector.findCandidates(vendorAdbProtocol).isEmpty())
    }

    @Test
    fun `candidate requires bulk in and out and uses the first pair`() {
        val incomplete = device(
            interfaces = listOf(
                fastbootInterface(
                    endpoints = listOf(bulkIn(0x81)),
                ),
            ),
        )
        val complete = device(
            name = "/dev/bus/usb/001/004",
            interfaces = listOf(
                fastbootInterface(
                    endpoints = listOf(
                        otherIn(0x84),
                        bulkIn(0x82),
                        bulkIn(0x83),
                        bulkOut(0x02),
                        bulkOut(0x03),
                    ),
                ),
            ),
        )

        assertTrue(UsbInterfaceSelector.findCandidates(incomplete).isEmpty())
        val candidate = UsbInterfaceSelector.findCandidates(complete).single()
        assertEquals(0x82, candidate.endpointInAddress)
        assertEquals(0x02, candidate.endpointOutAddress)
    }

    @Test
    fun `primary candidate keeps the lowest interface index within the winning class`() {
        val device = device(
            interfaces = listOf(adbInterface(), adbInterface()),
        )

        assertEquals(0, UsbInterfaceSelector.selectPrimaryCandidate(device)!!.interfaceIndex)
    }

    @Test
    fun `stable key tracks android attachment while logical signature tracks usb profile`() {
        val first = device(
            id = 10,
            name = "/dev/bus/usb/001/002",
            interfaces = listOf(fastbootInterface()),
        )
        val second = first.copy(
            deviceId = 11,
            deviceName = "/dev/bus/usb/002/009",
            productName = "renamed",
        )

        val firstCandidate = UsbInterfaceSelector.findCandidates(first).single()
        val secondCandidate = UsbInterfaceSelector.findCandidates(second).single()

        assertFalse(firstCandidate.stableKey == secondCandidate.stableKey)
        assertEquals(firstCandidate.logicalSignature, secondCandidate.logicalSignature)
    }

    @Test
    fun `all candidates are deduplicated by stable key and sorted deterministically`() {
        val adb = device(
            id = 3,
            name = "/dev/adb",
            productName = "Zulu",
            interfaces = listOf(adbInterface()),
        )
        val fastboot = device(
            id = 4,
            name = "/dev/fastboot",
            productName = "Alpha",
            interfaces = listOf(fastbootInterface()),
        )

        val candidates = UsbInterfaceSelector.findAllCandidates(listOf(fastboot, adb, adb))

        assertEquals(2, candidates.size)
        assertEquals(listOf(Mode.ADB, Mode.FASTBOOT), candidates.map { it.mode })
    }

    @Test
    fun `mode switch ignores the previous logical profile`() {
        val adb = device(interfaces = listOf(adbInterface()))
        val previous = UsbInterfaceSelector.findCandidates(adb).single()

        assertNull(
            UsbInterfaceSelector.selectModeSwitchCandidate(
                devices = listOf(adb),
                previousLogicalSignature = previous.logicalSignature,
                previousVendorId = adb.vendorId,
            ),
        )

        val fastboot = adb.copy(interfaces = listOf(fastbootInterface()))
        val changed = UsbInterfaceSelector.selectModeSwitchCandidate(
            devices = listOf(fastboot),
            previousLogicalSignature = previous.logicalSignature,
            previousVendorId = adb.vendorId,
        )
        assertEquals(Mode.FASTBOOT, changed!!.mode)
    }

    @Test
    fun `mode switch keeps vendor affinity when requested`() {
        val previous = UsbInterfaceSelector.findCandidates(device(interfaces = listOf(adbInterface()))).single()
        val otherVendor = device(
            id = 5,
            name = "/dev/other",
            vendorId = 0x18D1,
            interfaces = listOf(fastbootInterface()),
        )

        assertNull(
            UsbInterfaceSelector.selectModeSwitchCandidate(
                devices = listOf(otherVendor),
                previousLogicalSignature = previous.logicalSignature,
                previousVendorId = previous.device.vendorId,
            ),
        )
    }

    @Test
    fun `ambiguous changed profiles are not auto-selected during mode switch`() {
        val previous = UsbInterfaceSelector.findCandidates(device(interfaces = listOf(adbInterface()))).single()
        val first = device(
            id = 5,
            name = "/dev/first",
            interfaces = listOf(fastbootInterface()),
        )
        val second = device(
            id = 6,
            name = "/dev/second",
            interfaces = listOf(fastbootInterface(protocol = 0x7F)),
        )

        assertNull(
            UsbInterfaceSelector.selectModeSwitchCandidate(
                devices = listOf(first, second),
                previousLogicalSignature = previous.logicalSignature,
                previousVendorId = previous.device.vendorId,
            ),
        )
    }

    @Test
    fun `rebind prefers the same interface index for the same device name`() {
        val oldDevice = device(
            name = "/dev/bus/usb/001/002",
            interfaces = listOf(adbInterface()),
        )
        val previous = UsbInterfaceSelector.findCandidates(oldDevice).single()
        val reboundDevice = oldDevice.copy(
            deviceId = 99,
            productName = "Fresh descriptor",
        )

        val rebound = UsbInterfaceSelector.rebindCandidate(reboundDevice, previous)!!

        assertEquals(99, rebound.device.deviceId)
        assertEquals(0, rebound.interfaceIndex)
        assertEquals(previous.logicalSignature, rebound.logicalSignature)
    }

    @Test
    fun `rebind follows the logical signature when interface position changes`() {
        val oldDevice = device(
            interfaces = listOf(fastbootInterface(endpoints = pair(0x81, 0x01))),
        )
        val previous = UsbInterfaceSelector.findCandidates(oldDevice).single()
        val reboundDevice = oldDevice.copy(
            interfaces = listOf(
                usbInterface(subclass = 0x10, protocol = 0x01),
                fastbootInterface(endpoints = pair(0x81, 0x01)),
            ),
        )

        val rebound = UsbInterfaceSelector.rebindCandidate(reboundDevice, previous)!!

        assertEquals(1, rebound.interfaceIndex)
        assertEquals(previous.logicalSignature, rebound.logicalSignature)
    }

    @Test
    fun `rebind falls back only to a unique same mode and match kind`() {
        val oldDevice = device(interfaces = listOf(fastbootInterface(endpoints = pair(0x81, 0x01))))
        val previous = UsbInterfaceSelector.findCandidates(oldDevice).single()
        val unrelated = usbInterface(
            interfaceClass = 0x08,
            subclass = 0x06,
            protocol = 0x50,
        )
        val uniqueChanged = oldDevice.copy(
            interfaces = listOf(
                unrelated,
                fastbootInterface(endpoints = pair(0x82, 0x02)),
            ),
        )
        val ambiguousChanged = oldDevice.copy(
            interfaces = listOf(
                unrelated,
                fastbootInterface(endpoints = pair(0x82, 0x02)),
                fastbootInterface(endpoints = pair(0x83, 0x03)),
            ),
        )

        assertEquals(0x82, UsbInterfaceSelector.rebindCandidate(uniqueChanged, previous)!!.endpointInAddress)
        assertNull(UsbInterfaceSelector.rebindCandidate(ambiguousChanged, previous))
    }

    @Test
    fun `rebind rejects a different android device name`() {
        val oldDevice = device(interfaces = listOf(adbInterface()))
        val previous = UsbInterfaceSelector.findCandidates(oldDevice).single()
        val otherName = oldDevice.copy(deviceName = "/dev/bus/usb/009/009")

        assertNull(UsbInterfaceSelector.rebindCandidate(otherName, previous))
    }

    private fun device(
        id: Int = 1,
        name: String = "/dev/bus/usb/001/002",
        vendorId: Int = 0x2717,
        productId: Int = 0xFF48,
        productName: String? = "Target",
        interfaces: List<UsbInterfaceDescriptor>,
    ) = UsbDeviceDescriptor(
        deviceId = id,
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        productName = productName,
        interfaces = interfaces,
    )

    private fun adbInterface(
        endpoints: List<UsbEndpointDescriptor> = pair(0x81, 0x01),
    ) = usbInterface(subclass = 0x42, protocol = 0x01, endpoints = endpoints)

    private fun fastbootInterface(
        protocol: Int = 0x03,
        endpoints: List<UsbEndpointDescriptor> = pair(0x81, 0x01),
    ) = usbInterface(subclass = 0x42, protocol = protocol, endpoints = endpoints)

    private fun usbInterface(
        interfaceClass: Int = 0xFF,
        subclass: Int,
        protocol: Int,
        endpoints: List<UsbEndpointDescriptor> = pair(0x81, 0x01),
    ) = UsbInterfaceDescriptor(
        id = 0,
        interfaceClass = interfaceClass,
        interfaceSubclass = subclass,
        interfaceProtocol = protocol,
        endpoints = endpoints,
    )

    private fun pair(input: Int, output: Int) = listOf(bulkIn(input), bulkOut(output))

    private fun bulkIn(address: Int) = UsbEndpointDescriptor(
        address = address,
        direction = UsbEndpointDirection.IN,
        transferType = UsbTransferType.BULK,
    )

    private fun bulkOut(address: Int) = UsbEndpointDescriptor(
        address = address,
        direction = UsbEndpointDirection.OUT,
        transferType = UsbTransferType.BULK,
    )

    private fun otherIn(address: Int) = UsbEndpointDescriptor(
        address = address,
        direction = UsbEndpointDirection.IN,
        transferType = UsbTransferType.OTHER,
    )
}
