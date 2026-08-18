package io.github.ncorror.nekoflash

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.ui.entry.EntryGateScreen
import io.github.ncorror.nekoflash.ui.home.HomeScreen
import io.github.ncorror.nekoflash.ui.home.HomeUiState
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val app
        get() = application as NekoFlashApplication

    private val entrySessionGate
        get() = app.entrySessionGate

    private val usbSessionCoordinator
        get() = app.usbSessionCoordinator

    private var usbUiEntryGeneration: Long? = null
    private var diagnosticsExportMessage by mutableStateOf<String?>(null)
    private var diagnosticsExportInProgress by mutableStateOf(false)

    private val diagnosticsArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DIAGNOSTICS_ARCHIVE_MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            diagnosticsExportInProgress = false
            return@registerForActivityResult
        }
        if (!entrySessionGate.isSessionAuthorized()) {
            diagnosticsExportInProgress = false
            diagnosticsExportMessage = getString(R.string.diagnostics_export_session_ended)
            return@registerForActivityResult
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            diagnosticsExportInProgress = false
            diagnosticsExportMessage = getString(R.string.diagnostics_export_failed)
            return@registerForActivityResult
        }
        exportDiagnosticsTo(uri)
    }

    private val authorizedRootBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Pinned legacy MainActivity behavior: Back on the authorized root UI
            // backgrounds the task and keeps the entry session alive.
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authorizedAtCreate = entrySessionGate.isSessionAuthorized()
        authorizedRootBackCallback.isEnabled = authorizedAtCreate
        onBackPressedDispatcher.addCallback(this, authorizedRootBackCallback)
        if (authorizedAtCreate) {
            usbSessionCoordinator.start()
            usbUiEntryGeneration = usbSessionCoordinator.onActivityCreated(intent)
        }

        enableEdgeToEdge()
        setContent {
            var authorized by remember { mutableStateOf(authorizedAtCreate) }
            var riskAccepted by remember { mutableStateOf(entrySessionGate.isRiskAcknowledged()) }
            var errorResId by remember { mutableStateOf<Int?>(null) }

            NekoFlashTheme {
                if (authorized) {
                    HomeScreen(
                        state = HomeUiState(),
                        diagnosticsExportInProgress = diagnosticsExportInProgress,
                        diagnosticsExportMessage = diagnosticsExportMessage,
                        onExportDiagnostics = ::requestDiagnosticsExport,
                    )
                } else {
                    EntryGateScreen(
                        riskAccepted = riskAccepted,
                        onRiskAcceptedChange = {
                            riskAccepted = it
                            errorResId = null
                        },
                        errorMessage = errorResId?.let { stringResource(it) },
                        onContinue = {
                            if (entrySessionGate.authorize(riskAccepted)) {
                                usbSessionCoordinator.start()
                                usbUiEntryGeneration =
                                    usbSessionCoordinator.onEntryAuthorized(intent)
                                errorResId = null
                                authorizedRootBackCallback.isEnabled = true
                                authorized = true
                            } else {
                                errorResId = if (riskAccepted) {
                                    R.string.entry_acknowledgement_save_failed
                                } else {
                                    R.string.entry_risk_required
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun requestDiagnosticsExport() {
        if (!entrySessionGate.isSessionAuthorized() || diagnosticsExportInProgress) return
        diagnosticsExportInProgress = true
        diagnosticsExportMessage = null
        runCatching {
            diagnosticsArchiveLauncher.launch(
                usbSessionCoordinator.suggestedDiagnosticsArchiveFileName(),
            )
        }.onFailure {
            diagnosticsExportInProgress = false
            diagnosticsExportMessage = getString(R.string.diagnostics_export_failed)
        }
    }

    private fun exportDiagnosticsTo(uri: Uri) {
        diagnosticsExportMessage = getString(R.string.diagnostics_export_saving)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val result = runCatching {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: error("Document provider returned no output stream")
                    output.use { stream ->
                        usbSessionCoordinator.exportDiagnosticsArchive(stream)
                    }
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        diagnosticsExportInProgress = false
                        diagnosticsExportMessage = result.fold(
                            onSuccess = { exported ->
                                getString(R.string.diagnostics_export_saved, exported.sourceFileCount)
                            },
                            onFailure = {
                                getString(R.string.diagnostics_export_failed)
                            },
                        )
                    }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (entrySessionGate.isSessionAuthorized()) {
            usbSessionCoordinator.onActivityNewIntent(intent)
        }
    }

    override fun onDestroy() {
        usbUiEntryGeneration?.let { usbSessionCoordinator.onActivityDestroyed(it) }
        usbUiEntryGeneration = null
        if (!isChangingConfigurations) {
            entrySessionGate.endSession()
            usbSessionCoordinator.stop()
        }
        super.onDestroy()
    }

    private companion object {
        const val DIAGNOSTICS_ARCHIVE_MIME_TYPE = "application/zip"
    }
}
