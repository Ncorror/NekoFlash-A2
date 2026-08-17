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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.ui.components.StatusCard
import io.github.ncorror.nekoflash.ui.components.StatusTone
import io.github.ncorror.nekoflash.ui.theme.NekoFlashSpacing

private data class HomeStatusItem(
    val label: String,
    val value: String,
    val tone: StatusTone = StatusTone.Neutral,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.value_unknown)
    val statusItems = listOf(
        HomeStatusItem(
            label = stringResource(R.string.status_device),
            value = state.device ?: stringResource(R.string.device_not_connected),
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_mode),
            value = state.mode ?: unknown,
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_usb),
            value = if (state.usbConnected) {
                stringResource(R.string.usb_connected)
            } else {
                stringResource(R.string.usb_disconnected)
            },
            tone = if (state.usbConnected) StatusTone.Positive else StatusTone.Neutral,
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_slot),
            value = state.slot ?: unknown,
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_topology),
            value = state.topology ?: unknown,
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_unlock),
            value = state.unlockState ?: unknown,
        ),
        HomeStatusItem(
            label = stringResource(R.string.status_operation),
            value = state.activeOperation ?: stringResource(R.string.operation_none),
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
}
