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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbSessionCoordinator.onActivityIntent(intent)
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
        usbSessionCoordinator.onActivityIntent(intent)
    }
}
