package ru.forum.adbfastboottool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.forum.adbfastboottool.ui.home.HomeScreen
import ru.forum.adbfastboottool.ui.home.HomeUiState
import ru.forum.adbfastboottool.ui.theme.NekoFlashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NekoFlashTheme {
                HomeScreen(state = HomeUiState())
            }
        }
    }
}
