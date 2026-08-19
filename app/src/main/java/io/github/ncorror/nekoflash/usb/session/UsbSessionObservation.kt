package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate

/**
 * UI-safe snapshot of coordinator-owned USB discovery state.
 *
 * CANDIDATE_READY means descriptor + Android permission are ready for the next
 * transport stage. It intentionally does not mean that UsbDeviceConnection is
 * open or that an ADB/Fastboot handshake has completed.
 */
data class UsbSessionObservation(
    val status: Status = Status.INACTIVE,
    val physicalDeviceCount: Int = 0,
    val compatibleCandidateCount: Int = 0,
    val candidate: UsbCandidateSummary? = null,
) {
    enum class Status {
        INACTIVE,
        SCANNING,
        NO_DEVICE,
        UNSUPPORTED_DEVICE,
        MULTIPLE_CANDIDATES,
        PERMISSION_PENDING,
        PERMISSION_DENIED,
        PERMISSION_ERROR,
        CANDIDATE_READY,
    }
}

enum class UsbObservedMode {
    ADB,
    FASTBOOT,
}

data class UsbCandidateSummary(
    val stableKey: String,
    val deviceLabel: String,
    val mode: UsbObservedMode,
    val interfaceIndex: Int,
)

internal fun Candidate.toUsbCandidateSummary(): UsbCandidateSummary = UsbCandidateSummary(
    stableKey = stableKey,
    deviceLabel = device.productName?.takeIf(String::isNotBlank) ?: device.deviceName,
    mode = when (mode) {
        UsbInterfaceSelector.Mode.ADB -> UsbObservedMode.ADB
        UsbInterfaceSelector.Mode.FASTBOOT -> UsbObservedMode.FASTBOOT
    },
    interfaceIndex = interfaceIndex,
)
