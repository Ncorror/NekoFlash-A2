package io.github.ncorror.nekoflash.usb.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbTransferType

/** Maps Android framework descriptors into the pure USB model used by policy code. */
object AndroidUsbDescriptorMapper {
    fun map(device: UsbDevice): UsbDeviceDescriptor = UsbDeviceDescriptor(
        deviceId = device.deviceId,
        deviceName = device.deviceName,
        vendorId = device.vendorId,
        productId = device.productId,
        productName = runCatching { device.productName }.getOrNull(),
        interfaces = (0 until device.interfaceCount).map { index ->
            map(device.getInterface(index))
        },
    )

    private fun map(usbInterface: UsbInterface): UsbInterfaceDescriptor = UsbInterfaceDescriptor(
        id = usbInterface.id,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        endpoints = (0 until usbInterface.endpointCount).map { index ->
            map(usbInterface.getEndpoint(index))
        },
    )

    private fun map(endpoint: UsbEndpoint): UsbEndpointDescriptor = UsbEndpointDescriptor(
        address = endpoint.address,
        direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) {
            UsbEndpointDirection.IN
        } else {
            UsbEndpointDirection.OUT
        },
        transferType = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            UsbTransferType.BULK
        } else {
            UsbTransferType.OTHER
        },
        maxPacketSize = endpoint.maxPacketSize,
    )
}
