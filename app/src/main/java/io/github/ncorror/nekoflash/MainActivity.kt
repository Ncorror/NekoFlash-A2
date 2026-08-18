package io.github.ncorror.nekoflash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ncorror.nekoflash.ui.home.HomeScreen
import io.github.ncorror.nekoflash.ui.home.HomeUiState
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme

class MainActivity : ComponentActivity() {
    private val usbSessionCoordinator
        get() = (application as NekoFlashApplication).usbSessionCoordinator

    private var usbUiEntryGeneration: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbUiEntryGeneration = usbSessionCoordinator.onActivityCreated(intent)
        enableEdgeToEdge()
        setContent {
            NekoFlashTheme {
                HomeScreen(state = HomeUiState())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbSessionCoordinator.onActivityNewIntent(intent)
    }

    override fun onDestroy() {
        usbUiEntryGeneration?.let { usbSessionCoordinator.onActivityDestroyed(it) }
        usbUiEntryGeneration = null
        super.onDestroy()
    }
}
