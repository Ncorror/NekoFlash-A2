package io.github.ncorror.nekoflash.usb.discovery

import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbTransferType

/**
 * Pure descriptor classification for ADB/Fastboot USB interfaces.
 *
 * Descriptor matches only choose a candidate. A protocol handshake still owns
 * the final decision that the selected interface is a usable ADB/Fastboot peer.
 */
object UsbInterfaceSelector {
    enum class Mode {
        FASTBOOT,
        ADB,
    }

    enum class MatchKind(val rank: Int) {
        CANONICAL(0),
        COMPAT_FASTBOOT(1),
        GENERIC_FASTBOOT(2),
    }

    data class Candidate(
        val device: UsbDeviceDescriptor,
        val mode: Mode,
        val interfaceIndex: Int,
        val endpointInAddress: Int,
        val endpointOutAddress: Int,
        val matchKind: MatchKind,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
    ) {
        val stableKey: String
            get() = buildString {
                append(device.deviceName)
                append(':').append(device.vendorId)
                append(':').append(device.productId)
                append(':').append(mode.name)
                append(':').append(interfaceIndex)
            }

        val logicalSignature: String
            get() = buildString {
                append(device.vendorId)
                append(':').append(device.productId)
                append(':').append(mode.name)
                append(':').append(interfaceClass)
                append(':').append(interfaceSubclass)
                append(':').append(interfaceProtocol)
                append(':').append(endpointInAddress)
                append(':').append(endpointOutAddress)
            }
    }

    fun findCandidates(
        device: UsbDeviceDescriptor,
        includeGenericFastboot: Boolean = true,
    ): List<Candidate> {
        val canonical = mutableListOf<Candidate>()
        val compat = mutableListOf<Candidate>()
        val generic = mutableListOf<Candidate>()

        device.interfaces.forEachIndexed { interfaceIndex, usbInterface ->
            val endpoints = bulkEndpointPair(usbInterface) ?: return@forEachIndexed

            when {
                isCanonicalAdb(usbInterface) -> canonical += candidate(
                    device = device,
                    mode = Mode.ADB,
                    interfaceIndex = interfaceIndex,
                    usbInterface = usbInterface,
                    endpoints = endpoints,
                    matchKind = MatchKind.CANONICAL,
                )

                isCanonicalFastboot(usbInterface) -> canonical += candidate(
                    device = device,
                    mode = Mode.FASTBOOT,
                    interfaceIndex = interfaceIndex,
                    usbInterface = usbInterface,
                    endpoints = endpoints,
                    matchKind = MatchKind.CANONICAL,
                )

                isAndroidFastbootCompatible(usbInterface) -> compat += candidate(
                    device = device,
                    mode = Mode.FASTBOOT,
                    interfaceIndex = interfaceIndex,
                    usbInterface = usbInterface,
                    endpoints = endpoints,
                    matchKind = MatchKind.COMPAT_FASTBOOT,
                )

                includeGenericFastboot && isGenericVendorBulkPair(usbInterface) -> generic += candidate(
                    device = device,
                    mode = Mode.FASTBOOT,
                    interfaceIndex = interfaceIndex,
                    usbInterface = usbInterface,
                    endpoints = endpoints,
                    matchKind = MatchKind.GENERIC_FASTBOOT,
                )
            }
        }

        val canonicalAdb = canonical.filter { it.mode == Mode.ADB }
        if (canonicalAdb.isNotEmpty()) return canonicalAdb

        val canonicalFastboot = canonical.filter { it.mode == Mode.FASTBOOT }
        if (canonicalFastboot.isNotEmpty()) return canonicalFastboot

        if (compat.isNotEmpty()) return compat
        return generic
    }

    fun selectPrimaryCandidate(
        device: UsbDeviceDescriptor,
        allowGenericFastboot: Boolean = true,
    ): Candidate? = findCandidates(device, includeGenericFastboot = allowGenericFastboot)
        .minWithOrNull(
            compareBy<Candidate> { it.matchKind.rank }
                .thenBy { if (it.mode == Mode.ADB) 0 else 1 }
                .thenBy { it.interfaceIndex },
        )

    fun findAllCandidates(
        devices: Collection<UsbDeviceDescriptor>,
        includeGenericFastboot: Boolean = true,
    ): List<Candidate> = devices
        .flatMap { findCandidates(it, includeGenericFastboot) }
        .distinctBy { it.stableKey }
        .sortedWith(
            compareBy<Candidate> { it.matchKind.rank }
                .thenBy { it.mode.name }
                .thenBy { it.device.productName ?: it.device.deviceName }
                .thenBy { it.interfaceIndex },
        )

    fun findAutoConnectCandidates(devices: Collection<UsbDeviceDescriptor>): List<Candidate> =
        findAllCandidates(devices, includeGenericFastboot = true)

    /**
     * A mode switch is accepted only when exactly one changed logical USB profile
     * remains after optional vendor filtering.
     */
    fun selectModeSwitchCandidate(
        devices: Collection<UsbDeviceDescriptor>,
        previousLogicalSignature: String?,
        previousVendorId: Int? = null,
    ): Candidate? = findAutoConnectCandidates(devices)
        .filter { previousLogicalSignature == null || it.logicalSignature != previousLogicalSignature }
        .filter { previousVendorId == null || it.device.vendorId == previousVendorId }
        .singleOrNull()

    /**
     * Rebinds a previously selected candidate to a fresh descriptor object for
     * the same Android deviceName, preserving legacy permission-result behavior.
     */
    fun rebindCandidate(
        device: UsbDeviceDescriptor,
        previous: Candidate,
    ): Candidate? {
        if (device.deviceName != previous.device.deviceName) return null
        val includeGeneric = previous.matchKind == MatchKind.GENERIC_FASTBOOT

        val sameIndex = detectInterface(device, previous.interfaceIndex, includeGeneric)
        if (sameIndex != null &&
            sameIndex.mode == previous.mode &&
            sameIndex.matchKind == previous.matchKind
        ) {
            return sameIndex
        }

        val candidates = findCandidates(device, includeGenericFastboot = includeGeneric)
        return candidates.firstOrNull {
            it.mode == previous.mode && it.logicalSignature == previous.logicalSignature
        } ?: candidates.singleOrNull {
            it.mode == previous.mode && it.matchKind == previous.matchKind
        }
    }

    private fun detectInterface(
        device: UsbDeviceDescriptor,
        interfaceIndex: Int,
        includeGenericFastboot: Boolean,
    ): Candidate? {
        val usbInterface = device.interfaces.getOrNull(interfaceIndex) ?: return null
        val endpoints = bulkEndpointPair(usbInterface) ?: return null
        val (mode, matchKind) = when {
            isCanonicalAdb(usbInterface) -> Mode.ADB to MatchKind.CANONICAL
            isCanonicalFastboot(usbInterface) -> Mode.FASTBOOT to MatchKind.CANONICAL
            isAndroidFastbootCompatible(usbInterface) -> Mode.FASTBOOT to MatchKind.COMPAT_FASTBOOT
            includeGenericFastboot && isGenericVendorBulkPair(usbInterface) ->
                Mode.FASTBOOT to MatchKind.GENERIC_FASTBOOT
            else -> return null
        }
        return candidate(device, mode, interfaceIndex, usbInterface, endpoints, matchKind)
    }

    private fun candidate(
        device: UsbDeviceDescriptor,
        mode: Mode,
        interfaceIndex: Int,
        usbInterface: UsbInterfaceDescriptor,
        endpoints: Pair<UsbEndpointDescriptor, UsbEndpointDescriptor>,
        matchKind: MatchKind,
    ): Candidate = Candidate(
        device = device,
        mode = mode,
        interfaceIndex = interfaceIndex,
        endpointInAddress = endpoints.first.address,
        endpointOutAddress = endpoints.second.address,
        matchKind = matchKind,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
    )

    private fun isCanonicalAdb(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == ADB_PROTOCOL

    private fun isCanonicalFastboot(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == FASTBOOT_PROTOCOL

    private fun isAndroidFastbootCompatible(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol != ADB_PROTOCOL &&
            usbInterface.interfaceProtocol != FASTBOOT_PROTOCOL

    private fun isGenericVendorBulkPair(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPEC &&
            usbInterface.interfaceProtocol != ADB_PROTOCOL

    private fun bulkEndpointPair(
        usbInterface: UsbInterfaceDescriptor,
    ): Pair<UsbEndpointDescriptor, UsbEndpointDescriptor>? {
        var input: UsbEndpointDescriptor? = null
        var output: UsbEndpointDescriptor? = null
        for (endpoint in usbInterface.endpoints) {
            if (endpoint.transferType != UsbTransferType.BULK) continue
            if (endpoint.direction == UsbEndpointDirection.IN && input == null) input = endpoint
            if (endpoint.direction == UsbEndpointDirection.OUT && output == null) output = endpoint
        }
        return if (input != null && output != null) input to output else null
    }

    private const val USB_CLASS_VENDOR_SPEC = 0xFF
    private const val ANDROID_USB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01
    private const val FASTBOOT_PROTOCOL = 0x03
}
