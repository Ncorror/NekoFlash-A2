package io.github.ncorror.nekoflash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ncorror.nekoflash.ui.theme.NekoFlashSpacing

enum class StatusTone {
    Neutral,
    Positive,
}

@Composable
fun StatusCard(
    label: String,
    value: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
        StatusTone.Positive -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurface
        StatusTone.Positive -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(NekoFlashSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NekoFlashSpacing.inline),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.72f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
