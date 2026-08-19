package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Mode
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbTransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbManualScanPolicyTest {
    @Test
    fun `empty inventory reports no candidate without retry`() {
        val decision = UsbManualScanPolicy.decide(current = null, devices = emptyList())

        assertTrue(decision is UsbManualScanDecision.NoCandidate)
        assertEquals(0, decision.physicalDeviceCount)
        assertEquals(0, decision.compatibleCandidateCount)
    }

    @Test
    fun `physical unsupported device remains visible as inventory without candidate`() {
        val decision = UsbManualScanPolicy.decide(
            current = null,
            devices = listOf(device(id = 1, recognized = false)),
        )

        assertTrue(decision is UsbManualScanDecision.NoCandidate)
        assertEquals(1, decision.physicalDeviceCount)
        assertEquals(0, decision.compatibleCandidateCount)
    }

    @Test
    fun `single compatible candidate is selected by one explicit scan`() {
        val decision = UsbManualScanPolicy.decide(
            current = null,
            devices = listOf(device(id = 1)),
        )

        assertTrue(decision is UsbManualScanDecision.Select)
        decision as UsbManualScanDecision.Select
        assertEquals(1, decision.candidate.device.deviceId)
        assertEquals(Mode.ADB, decision.candidate.mode)
    }

    @Test
    fun `single generic fastboot candidate requires legacy confirmation`() {
        val decision = UsbManualScanPolicy.decide(
            current = null,
            devices = listOf(device(id = 4, genericFastboot = true)),
        )

        assertTrue(decision is UsbManualScanDecision.ConfirmGenericFastboot)
        decision as UsbManualScanDecision.ConfirmGenericFastboot
        assertEquals(UsbInterfaceSelector.MatchKind.GENERIC_FASTBOOT, decision.candidate.matchKind)
    }

    @Test
    fun `multiple candidates require explicit user choice`() {
        val decision = UsbManualScanPolicy.decide(
            current = null,
            devices = listOf(
                device(id = 2, name = "/dev/2"),
                device(id = 1, name = "/dev/1"),
            ),
        )

        assertTrue(decision is UsbManualScanDecision.Choose)
        decision as UsbManualScanDecision.Choose
        assertEquals(2, decision.candidates.size)
        assertEquals(listOf(1, 2), decision.candidates.map { it.device.deviceId })
    }

    @Test
    fun `current plus another candidate still requires legacy style user choice`() {
        val current = UsbInterfaceSelector.selectPrimaryCandidate(device(id = 1, name = "/dev/1"))!!

        val decision = UsbManualScanPolicy.decide(
            current = current,
            devices = listOf(device(id = 1, name = "/dev/1"), device(id = 2, name = "/dev/2")),
        )

        assertTrue(decision is UsbManualScanDecision.Choose)
        decision as UsbManualScanDecision.Choose
        assertEquals(2, decision.candidates.size)
    }

    @Test
    fun `explicit generic chooser selection still requires confirmation`() {
        val generic = UsbInterfaceSelector.selectPrimaryCandidate(
            device(id = 5, genericFastboot = true),
        )!!

        val decision = UsbManualScanPolicy.decideChosen(generic, physicalDeviceCount = 2)

        assertTrue(decision is UsbManualScanDecision.ConfirmGenericFastboot)
        assertEquals(2, decision.physicalDeviceCount)
    }

    @Test
    fun `explicit canonical chooser selection advances without generic confirmation`() {
        val canonical = UsbInterfaceSelector.selectPrimaryCandidate(device(id = 6))!!

        val decision = UsbManualScanPolicy.decideChosen(canonical, physicalDeviceCount = 2)

        assertTrue(decision is UsbManualScanDecision.Select)
        decision as UsbManualScanDecision.Select
        assertEquals(6, decision.candidate.device.deviceId)
    }

    @Test
    fun `sole current canonical candidate still advances through legacy access path`() {
        val originalDevice = device(id = 1, name = "/dev/current")
        val current = UsbInterfaceSelector.selectPrimaryCandidate(originalDevice)!!
        val refreshedDevice = originalDevice.copy(deviceId = 99)

        val decision = UsbManualScanPolicy.decide(current, listOf(refreshedDevice))

        assertTrue(decision is UsbManualScanDecision.Select)
        decision as UsbManualScanDecision.Select
        assertEquals(99, decision.candidate.device.deviceId)
        assertEquals(current.stableKey, decision.candidate.stableKey)
    }

    @Test
    fun `one empty manual enumeration does not invalidate current generation`() {
        val current = UsbInterfaceSelector.selectPrimaryCandidate(device(id = 7))!!

        val decision = UsbManualScanPolicy.decide(current, emptyList())

        assertTrue(decision is UsbManualScanDecision.PreserveCurrent)
        decision as UsbManualScanDecision.PreserveCurrent
        assertSame(current, decision.candidate)
        assertEquals(0, decision.physicalDeviceCount)
    }

    @Test
    fun `candidate summary exposes only ui safe descriptor identity`() {
        val candidate = UsbInterfaceSelector.selectPrimaryCandidate(
            device(id = 3, productName = null),
        )!!

        val summary = candidate.toUsbCandidateSummary()

        assertEquals("/dev/3", summary.deviceLabel)
        assertEquals(UsbObservedMode.ADB, summary.mode)
        assertEquals(0, summary.interfaceIndex)
    }


    @Test
    fun `fresh chooser revalidation rejects stale stable key`() {
        val decision = UsbManualScanPolicy.decideChosen(
            stableKey = "missing",
            devices = listOf(device(id = 1)),
        )

        assertEquals(null, decision)
    }

    @Test
    fun `fresh chooser revalidation returns candidate from new descriptor snapshot`() {
        val original = UsbInterfaceSelector.selectPrimaryCandidate(
            device(id = 1, name = "/dev/current"),
        )!!
        val refreshed = device(id = 99, name = "/dev/current")

        val decision = UsbManualScanPolicy.decideChosen(
            stableKey = original.stableKey,
            devices = listOf(refreshed),
        )

        assertTrue(decision is UsbManualScanDecision.Select)
        decision as UsbManualScanDecision.Select
        assertEquals(99, decision.candidate.device.deviceId)
        assertEquals(original.stableKey, decision.candidate.stableKey)
    }


    @Test
    fun `fresh chooser revalidation selects requested candidate from ambiguous inventory`() {
        val first = UsbInterfaceSelector.selectPrimaryCandidate(device(id = 1, name = "/dev/1"))!!

        val decision = UsbManualScanPolicy.decideChosen(
            stableKey = first.stableKey,
            devices = listOf(
                device(id = 2, name = "/dev/2"),
                device(id = 10, name = "/dev/1"),
            ),
        )

        assertTrue(decision is UsbManualScanDecision.Select)
        decision as UsbManualScanDecision.Select
        assertEquals(10, decision.candidate.device.deviceId)
        assertEquals(2, decision.physicalDeviceCount)
    }

    @Test
    fun `fresh chooser revalidation preserves generic confirmation requirement`() {
        val generic = UsbInterfaceSelector.selectPrimaryCandidate(
            device(id = 5, genericFastboot = true),
        )!!

        val decision = UsbManualScanPolicy.decideChosen(
            stableKey = generic.stableKey,
            devices = listOf(device(id = 55, name = "/dev/5", genericFastboot = true)),
        )

        assertTrue(decision is UsbManualScanDecision.ConfirmGenericFastboot)
        decision as UsbManualScanDecision.ConfirmGenericFastboot
        assertEquals(55, decision.candidate.device.deviceId)
        assertEquals(UsbInterfaceSelector.MatchKind.GENERIC_FASTBOOT, decision.candidate.matchKind)
    }

    private fun device(
        id: Int,
        name: String = "/dev/$id",
        productName: String? = "target-$id",
        recognized: Boolean = true,
        genericFastboot: Boolean = false,
    ): UsbDeviceDescriptor = UsbDeviceDescriptor(
        deviceId = id,
        deviceName = name,
        vendorId = 0x18D1,
        productId = 0x4EE7,
        productName = productName,
        interfaces = listOf(
            if (recognized) {
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = 0xFF,
                    interfaceSubclass = if (genericFastboot) 0x10 else 0x42,
                    interfaceProtocol = if (genericFastboot) 0x55 else 0x01,
                    endpoints = listOf(
                        UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
                        UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
                    ),
                )
            } else {
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = 0x08,
                    interfaceSubclass = 0x06,
                    interfaceProtocol = 0x50,
                    endpoints = emptyList(),
                )
            },
        ),
    )
}
