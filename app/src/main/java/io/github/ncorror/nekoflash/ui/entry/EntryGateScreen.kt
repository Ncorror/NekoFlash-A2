package io.github.ncorror.nekoflash.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.ui.theme.NekoFlashSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryGateScreen(
    riskAccepted: Boolean,
    onRiskAcceptedChange: (Boolean) -> Unit,
    errorMessage: String?,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .padding(horizontal = NekoFlashSpacing.screenHorizontal)
                .verticalScroll(rememberScrollState())
                .padding(bottom = NekoFlashSpacing.screenBottom),
            verticalArrangement = Arrangement.spacedBy(NekoFlashSpacing.section),
        ) {
            Text(
                text = stringResource(R.string.entry_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.entry_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NekoFlashSpacing.inline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = riskAccepted,
                    onCheckedChange = onRiskAcceptedChange,
                )
                Text(
                    text = stringResource(R.string.entry_risk_acknowledgement),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.entry_continue))
            }
        }
    }
}
