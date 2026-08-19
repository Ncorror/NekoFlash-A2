package io.github.ncorror.nekoflash.usb.session

import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector
import io.github.ncorror.nekoflash.usb.discovery.UsbInterfaceSelector.Candidate
import io.github.ncorror.nekoflash.usb.model.UsbDeviceDescriptor

sealed interface UsbManualScanDecision {
    val physicalDeviceCount: Int
    val compatibleCandidateCount: Int

    data class NoCandidate(
        override val physicalDeviceCount: Int,
    ) : UsbManualScanDecision {
        override val compatibleCandidateCount: Int = 0
    }

    data class PreserveCurrent(
        val candidate: Candidate,
        override val physicalDeviceCount: Int,
    ) : UsbManualScanDecision {
        override val compatibleCandidateCount: Int = 0
    }

    data class Select(
        val candidate: Candidate,
        override val physicalDeviceCount: Int,
    ) : UsbManualScanDecision {
        override val compatibleCandidateCount: Int = 1
    }

    data class ConfirmGenericFastboot(
        val candidate: Candidate,
        override val physicalDeviceCount: Int,
    ) : UsbManualScanDecision {
        override val compatibleCandidateCount: Int = 1
    }

    data class Choose(
        val candidates: List<Candidate>,
        override val physicalDeviceCount: Int,
    ) : UsbManualScanDecision {
        override val compatibleCandidateCount: Int = candidates.size
    }
}

sealed interface UsbManualScanResult {
    data object Inactive : UsbManualScanResult
    data object Completed : UsbManualScanResult
    data class ConfirmGenericFastboot(val candidate: UsbCandidateSummary) : UsbManualScanResult
    data class Choose(val candidates: List<UsbCandidateSummary>) : UsbManualScanResult
}

/**
 * Pure policy for the legacy explicit Search action.
 *
 * A user action performs exactly one fresh device-list enumeration. It does not
 * create a background retry loop. Zero candidates are reported, one candidate
 * may advance, and ambiguous results require explicit user choice.
 */
object UsbManualScanPolicy {
    fun decide(
        current: Candidate?,
        devices: Collection<UsbDeviceDescriptor>,
    ): UsbManualScanDecision {
        val candidates = UsbInterfaceSelector.findAllCandidates(
            devices = devices,
            includeGenericFastboot = true,
        )
        if (candidates.size > 1) {
            return UsbManualScanDecision.Choose(candidates, devices.size)
        }
        if (candidates.size == 1) {
            val only = candidates.single()
            if (only.matchKind == UsbInterfaceSelector.MatchKind.GENERIC_FASTBOOT) {
                return UsbManualScanDecision.ConfirmGenericFastboot(only, devices.size)
            }
            return UsbManualScanDecision.Select(only, devices.size)
        }
        if (current != null) {
            // Legacy Search reports an empty pass but does not tear down the
            // existing connection; detach remains the invalidation boundary.
            return UsbManualScanDecision.PreserveCurrent(
                candidate = current,
                physicalDeviceCount = devices.size,
            )
        }
        return UsbManualScanDecision.NoCandidate(devices.size)
    }

    fun decideChosen(
        candidate: Candidate,
        physicalDeviceCount: Int,
    ): UsbManualScanDecision =
        if (candidate.matchKind == UsbInterfaceSelector.MatchKind.GENERIC_FASTBOOT) {
            UsbManualScanDecision.ConfirmGenericFastboot(candidate, physicalDeviceCount)
        } else {
            UsbManualScanDecision.Select(candidate, physicalDeviceCount)
        }
}
