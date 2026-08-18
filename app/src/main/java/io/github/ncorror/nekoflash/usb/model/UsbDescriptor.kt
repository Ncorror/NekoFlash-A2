package io.github.ncorror.nekoflash.usb.model

/** Pure USB descriptors used before any Android UsbDeviceConnection is opened. */
data class UsbDeviceDescriptor(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String? = null,
    val interfaces: List<UsbInterfaceDescriptor>,
)

data class UsbInterfaceDescriptor(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDescriptor>,
)

data class UsbEndpointDescriptor(
    val address: Int,
    val direction: UsbEndpointDirection,
    val transferType: UsbTransferType,
    val maxPacketSize: Int = 0,
)

enum class UsbEndpointDirection {
    IN,
    OUT,
}

enum class UsbTransferType {
    BULK,
    OTHER,
}
