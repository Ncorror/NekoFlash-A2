package io.github.ncorror.nekoflash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.ui.entry.EntryGateScreen
import io.github.ncorror.nekoflash.ui.home.HomeScreen
import io.github.ncorror.nekoflash.ui.home.HomeUiState
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme

class MainActivity : ComponentActivity() {
    private val app
        get() = application as NekoFlashApplication

    private val entrySessionGate
        get() = app.entrySessionGate

    private val usbSessionCoordinator
        get() = app.usbSessionCoordinator

    private var usbUiEntryGeneration: Long? = null

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
                    HomeScreen(state = HomeUiState())
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
}
