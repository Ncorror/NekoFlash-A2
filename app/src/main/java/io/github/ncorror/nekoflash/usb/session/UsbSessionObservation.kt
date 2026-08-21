package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate

/**
 * UI-safe snapshot of coordinator-owned USB discovery and transport state.
 *
 * CANDIDATE_READY still means descriptor + Android permission only. ADB and Fastboot
 * transport states are reported independently so Stage 6A semantics cannot be mistaken for
 * a completed protocol handshake.
 */
data class UsbSessionObservation(
    val status: Status = Status.INACTIVE,
    val physicalDeviceCount: Int = 0,
    val compatibleCandidateCount: Int = 0,
    val candidate: UsbCandidateSummary? = null,
    val adbTransport: AdbTransportObservation = AdbTransportObservation(),
    val fastbootTransport: FastbootTransportObservation = FastbootTransportObservation(),
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

data class AdbTransportObservation(
    val status: Status = Status.INACTIVE,
    val peerMode: AdbObservedPeerMode? = null,
) {
    enum class Status {
        INACTIVE,
        CONNECTING,
        AUTHORIZING,
        CONNECTED,
        ERROR,
    }
}

data class FastbootTransportObservation(
    val status: Status = Status.INACTIVE,
    val product: String? = null,
) {
    enum class Status {
        INACTIVE,
        CONNECTING,
        CONNECTED,
        ERROR,
    }
}

enum class AdbObservedPeerMode {
    DEVICE,
    RECOVERY,
    SIDELOAD,
    UNKNOWN,
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
