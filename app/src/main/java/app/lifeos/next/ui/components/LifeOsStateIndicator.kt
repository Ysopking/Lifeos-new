package app.lifeos.next.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.lifeos.next.ui.theme.LifeOsTokens

enum class LifeOsStateKind(val visibleLabel: String) {
    POSITIVE("Bereit"),
    NEUTRAL("Neutral"),
    WARNING("Achtung"),
    NEGATIVE("Fehler"),
    MUTED("Inaktiv"),
}

@Composable
fun LifeOsStateIndicator(
    label: String,
    state: LifeOsStateKind,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            stateDescription = "$label: ${state.visibleLabel}"
        },
        horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(stateColor(state), CircleShape),
        )
        Text(
            text = "$label · ${state.visibleLabel}",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun stateColor(state: LifeOsStateKind): Color = when (state) {
    LifeOsStateKind.POSITIVE -> MaterialTheme.colorScheme.primary
    LifeOsStateKind.NEUTRAL -> MaterialTheme.colorScheme.secondary
    LifeOsStateKind.WARNING -> MaterialTheme.colorScheme.tertiary
    LifeOsStateKind.NEGATIVE -> MaterialTheme.colorScheme.error
    LifeOsStateKind.MUTED -> MaterialTheme.colorScheme.outline
}
