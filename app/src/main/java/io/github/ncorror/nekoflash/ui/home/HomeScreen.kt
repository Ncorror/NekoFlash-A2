package io.github.ncorror.nekoflash.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.ui.components.StatusCard
import io.github.ncorror.nekoflash.ui.components.StatusTone
import io.github.ncorror.nekoflash.ui.theme.NekoFlashSpacing
import io.github.ncorror.nekoflash.usb.session.AdbObservedPeerMode
import io.github.ncorror.nekoflash.usb.session.AdbTransportObservation
import io.github.ncorror.nekoflash.usb.session.FastbootTransportObservation
import io.github.ncorror.nekoflash.usb.session.UsbCandidateSummary
import io.github.ncorror.nekoflash.usb.session.UsbManualScanPrompt
import io.github.ncorror.nekoflash.usb.session.UsbObservedMode
import io.github.ncorror.nekoflash.usb.session.UsbSessionObservation

private data class HomeStatusItem(
    val label: String,
    val value: String,
    val tone: StatusTone = StatusTone.Neutral,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    observation: UsbSessionObservation,
    manualScanPrompt: UsbManualScanPrompt?,
    diagnosticsExportInProgress: Boolean,
    diagnosticsExportMessage: String?,
    onRefreshUsb: () -> Unit,
    onRefreshFastbootDiagnostics: () -> Unit,
    onManualCandidateChosen: (String) -> Unit,
    onConfirmGenericFastboot: (String) -> Unit,
    onDismissManualPrompt: () -> Unit,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidate = observation.candidate
    val statusItems = listOf(
        HomeStatusItem(
            label = stringResource(R.string.status_device),
            value = candidate?.deviceLabel ?: stringResource(R.string.device_not_detected),
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_mode),
            value = candidate?.mode?.localizedLabel() ?: stringResource(R.string.usb_status_not_detected),
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_usb),
            value = observation.status.localizedLabel(),
            tone = if (observation.status == UsbSessionObservation.Status.CANDIDATE_READY) {
                StatusTone.Positive
            } else {
                StatusTone.Neutral
            },
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_adb),
            value = observation.adbTransport.localizedLabel(),
            tone = if (observation.adbTransport.status == AdbTransportObservation.Status.CONNECTED) {
                StatusTone.Positive
            } else {
                StatusTone.Neutral
            },
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_fastboot),
            value = observation.fastbootTransport.localizedLabel(),
            tone = if (observation.fastbootTransport.status == FastbootTransportObservation.Status.CONNECTED) {
                StatusTone.Positive
            } else {
                StatusTone.Neutral
            },
        ),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = NekoFlashSpacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(NekoFlashSpacing.section),
        ) {
            Text(
                text = stringResource(R.string.home_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.home_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRefreshUsb,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.usb_refresh_button))
            }
            OutlinedButton(
                onClick = onRefreshFastbootDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                enabled = observation.fastbootTransport.status == FastbootTransportObservation.Status.CONNECTED,
            ) {
                Text(text = stringResource(R.string.fastboot_refresh_diagnostics_button))
            }
            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                enabled = !diagnosticsExportInProgress,
            ) {
                Text(
                    text = stringResource(
                        if (diagnosticsExportInProgress) {
                            R.string.diagnostics_export_saving
                        } else {
                            R.string.diagnostics_export_button
                        },
                    ),
                )
            }
            if (diagnosticsExportMessage != null) {
                Text(
                    text = diagnosticsExportMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = NekoFlashSpacing.statusCardMinWidth),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = NekoFlashSpacing.screenBottom),
                horizontalArrangement = Arrangement.spacedBy(NekoFlashSpacing.cardGap),
                verticalArrangement = Arrangement.spacedBy(NekoFlashSpacing.cardGap),
            ) {
                items(statusItems) { item ->
                    StatusCard(
                        label = item.label,
                        value = item.value,
                        tone = item.tone,
                    )
                }
            }
        }
    }

    manualScanPrompt?.let { prompt ->
        when (prompt) {
            is UsbManualScanPrompt.Choose -> UsbCandidateChooserDialog(
                candidates = prompt.candidates,
                onChosen = onManualCandidateChosen,
                onDismiss = onDismissManualPrompt,
            )

            is UsbManualScanPrompt.ConfirmGenericFastboot -> GenericFastbootConfirmationDialog(
                candidate = prompt.candidate,
                onConfirm = onConfirmGenericFastboot,
                onDismiss = onDismissManualPrompt,
            )
        }
    }
}

@Composable
private fun UsbCandidateChooserDialog(
    candidates: List<UsbCandidateSummary>,
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.usb_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NekoFlashSpacing.inline)) {
                candidates.forEach { candidate ->
                    TextButton(
                        onClick = { onChosen(candidate.stableKey) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = candidate.deviceLabel)
                            Text(
                                text = stringResource(
                                    R.string.usb_candidate_details,
                                    candidate.mode.localizedLabel(),
                                    candidate.interfaceIndex,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GenericFastbootConfirmationDialog(
    candidate: UsbCandidateSummary,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.usb_generic_fastboot_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.usb_generic_fastboot_message,
                    candidate.deviceLabel,
                    candidate.interfaceIndex,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(candidate.stableKey) }) {
                Text(text = stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun UsbObservedMode.localizedLabel(): String = when (this) {
    UsbObservedMode.ADB -> stringResource(R.string.usb_mode_adb)
    UsbObservedMode.FASTBOOT -> stringResource(R.string.usb_mode_fastboot)
}

@Composable
private fun UsbSessionObservation.Status.localizedLabel(): String = stringResource(
    when (this) {
        UsbSessionObservation.Status.INACTIVE -> R.string.usb_status_inactive
        UsbSessionObservation.Status.SCANNING -> R.string.usb_status_scanning
        UsbSessionObservation.Status.NO_DEVICE -> R.string.usb_status_not_detected
        UsbSessionObservation.Status.UNSUPPORTED_DEVICE -> R.string.usb_status_unsupported
        UsbSessionObservation.Status.MULTIPLE_CANDIDATES -> R.string.usb_status_multiple
        UsbSessionObservation.Status.PERMISSION_PENDING -> R.string.usb_status_permission_pending
        UsbSessionObservation.Status.PERMISSION_DENIED -> R.string.usb_status_permission_denied
        UsbSessionObservation.Status.PERMISSION_ERROR -> R.string.usb_status_permission_error
        UsbSessionObservation.Status.CANDIDATE_READY -> R.string.usb_status_detected
    },
)

@Composable
private fun AdbTransportObservation.localizedLabel(): String = when (status) {
    AdbTransportObservation.Status.INACTIVE -> stringResource(R.string.adb_status_inactive)
    AdbTransportObservation.Status.CONNECTING -> stringResource(R.string.adb_status_connecting)
    AdbTransportObservation.Status.AUTHORIZING -> stringResource(R.string.adb_status_authorizing)
    AdbTransportObservation.Status.ERROR -> stringResource(R.string.adb_status_error)
    AdbTransportObservation.Status.CONNECTED -> stringResource(
        R.string.adb_status_connected,
        peerMode.localizedLabel(),
    )
}

@Composable
private fun AdbObservedPeerMode?.localizedLabel(): String = when (this) {
    AdbObservedPeerMode.DEVICE -> stringResource(R.string.adb_peer_device)
    AdbObservedPeerMode.RECOVERY -> stringResource(R.string.adb_peer_recovery)
    AdbObservedPeerMode.SIDELOAD -> stringResource(R.string.adb_peer_sideload)
    AdbObservedPeerMode.UNKNOWN, null -> stringResource(R.string.adb_peer_unknown)
}

@Composable
private fun FastbootTransportObservation.localizedLabel(): String = when (status) {
    FastbootTransportObservation.Status.INACTIVE -> stringResource(R.string.fastboot_status_inactive)
    FastbootTransportObservation.Status.CONNECTING -> stringResource(R.string.fastboot_status_connecting)
    FastbootTransportObservation.Status.ERROR -> stringResource(R.string.fastboot_status_error)
    FastbootTransportObservation.Status.CONNECTED -> if (product.isNullOrBlank()) {
        stringResource(R.string.fastboot_status_connected_peer)
    } else {
        stringResource(R.string.fastboot_status_connected_product, product)
    }
}
