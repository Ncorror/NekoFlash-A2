package io.github.ncorror.nekoflash.usb.diagnostics

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.model.UsbInterfaceDescriptor

/** Immutable evidence captured before any protocol transport is opened. */
data class UsbSessionSnapshot(
    val sessionId: String,
    val capturedAtEpochMs: Long,
    val mode: String,
    val matchKind: String,
    val device: UsbDeviceDescriptor,
    val selectedInterfaceIndex: Int,
    val selectedEndpointInAddress: Int,
    val selectedEndpointOutAddress: Int,
    val host: HostSnapshot,
) {
    data class HostSnapshot(
        val sdkInt: Int,
        val release: String,
        val manufacturer: String,
        val model: String,
        val device: String,
    )

    fun toDiagnosticText(): String = buildString {
        appendLine("schema=$SCHEMA")
        appendLine("sessionId=$sessionId")
        appendLine("capturedAtEpochMs=$capturedAtEpochMs")
        appendLine("mode=$mode")
        appendLine("matchKind=$matchKind")
        appendLine("deviceName=${device.deviceName}")
        appendLine("productName=${device.productName.orEmpty()}")
        appendLine("vid=${device.vendorId}")
        appendLine("pid=${device.productId}")
        appendLine("selectedInterfaceIndex=$selectedInterfaceIndex")
        appendLine("selectedEndpointIn=${hex(selectedEndpointInAddress)}")
        appendLine("selectedEndpointOut=${hex(selectedEndpointOutAddress)}")
        appendLine("hostSdk=${host.sdkInt}")
        appendLine("hostRelease=${host.release}")
        appendLine("hostManufacturer=${host.manufacturer}")
        appendLine("hostModel=${host.model}")
        appendLine("hostDevice=${host.device}")
        device.interfaces.forEachIndexed { index, usbInterface ->
            appendLine(
                "interface[$index]=id=${usbInterface.id},class=${usbInterface.interfaceClass}," +
                    "subclass=${usbInterface.interfaceSubclass},protocol=${usbInterface.interfaceProtocol}",
            )
            usbInterface.endpoints.forEachIndexed { endpointIndex, endpoint ->
                appendLine(
                    "interface[$index].endpoint[$endpointIndex]=" +
                        "address=${hex(endpoint.address)},direction=${endpoint.direction.name}," +
                        "type=${endpoint.transferType.name},maxPacketSize=${endpoint.maxPacketSize}",
                )
            }
        }
    }

    fun toJson(): String = buildString {
        append('{')
        appendJsonField("schema", SCHEMA)
        append(',')
        appendJsonField("sessionId", sessionId)
        append(',')
        appendJsonNumber("capturedAtEpochMs", capturedAtEpochMs)
        append(',')
        appendJsonField("mode", mode)
        append(',')
        appendJsonField("matchKind", matchKind)
        append(',')
        append("\"device\":")
        appendDevice(device)
        append(',')
        appendJsonNumber("selectedInterfaceIndex", selectedInterfaceIndex.toLong())
        append(',')
        appendJsonNumber("selectedEndpointInAddress", selectedEndpointInAddress.toLong())
        append(',')
        appendJsonNumber("selectedEndpointOutAddress", selectedEndpointOutAddress.toLong())
        append(',')
        append("\"host\":{")
        appendJsonNumber("sdkInt", host.sdkInt.toLong())
        append(',')
        appendJsonField("release", host.release)
        append(',')
        appendJsonField("manufacturer", host.manufacturer)
        append(',')
        appendJsonField("model", host.model)
        append(',')
        appendJsonField("device", host.device)
        append("}}")
    }

    private fun StringBuilder.appendDevice(device: UsbDeviceDescriptor) {
        append('{')
        appendJsonNumber("deviceId", device.deviceId.toLong())
        append(',')
        appendJsonField("deviceName", device.deviceName)
        append(',')
        appendJsonNumber("vendorId", device.vendorId.toLong())
        append(',')
        appendJsonNumber("productId", device.productId.toLong())
        append(',')
        if (device.productName == null) {
            append("\"productName\":null")
        } else {
            appendJsonField("productName", device.productName)
        }
        append(',')
        append("\"interfaces\":[")
        device.interfaces.forEachIndexed { index, usbInterface ->
            if (index > 0) append(',')
            appendInterface(usbInterface)
        }
        append("]}")
    }

    private fun StringBuilder.appendInterface(usbInterface: UsbInterfaceDescriptor) {
        append('{')
        appendJsonNumber("id", usbInterface.id.toLong())
        append(',')
        appendJsonNumber("class", usbInterface.interfaceClass.toLong())
        append(',')
        appendJsonNumber("subclass", usbInterface.interfaceSubclass.toLong())
        append(',')
        appendJsonNumber("protocol", usbInterface.interfaceProtocol.toLong())
        append(',')
        append("\"endpoints\":[")
        usbInterface.endpoints.forEachIndexed { index, endpoint ->
            if (index > 0) append(',')
            appendEndpoint(endpoint)
        }
        append("]}")
    }

    private fun StringBuilder.appendEndpoint(endpoint: UsbEndpointDescriptor) {
        append('{')
        appendJsonNumber("address", endpoint.address.toLong())
        append(',')
        appendJsonField("direction", endpoint.direction.name)
        append(',')
        appendJsonField("transferType", endpoint.transferType.name)
        append(',')
        appendJsonNumber("maxPacketSize", endpoint.maxPacketSize.toLong())
        append('}')
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"').append(escapeJson(name)).append("\":\"")
        append(escapeJson(value))
        append('"')
    }

    private fun StringBuilder.appendJsonNumber(name: String, value: Long) {
        append('"').append(escapeJson(name)).append("\":").append(value)
    }

    companion object {
        const val SCHEMA = "io.github.ncorror.nekoflash.usb-session.v1"

        fun capture(
            sessionId: String,
            capturedAtEpochMs: Long,
            candidate: Candidate,
            host: HostSnapshot,
        ): UsbSessionSnapshot = UsbSessionSnapshot(
            sessionId = sessionId,
            capturedAtEpochMs = capturedAtEpochMs,
            mode = candidate.mode.name,
            matchKind = candidate.matchKind.name,
            device = candidate.device,
            selectedInterfaceIndex = candidate.interfaceIndex,
            selectedEndpointInAddress = candidate.endpointInAddress,
            selectedEndpointOutAddress = candidate.endpointOutAddress,
            host = host,
        )

        private fun hex(value: Int): String = "0x" + value.toString(16).padStart(2, '0')

        private fun escapeJson(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}
