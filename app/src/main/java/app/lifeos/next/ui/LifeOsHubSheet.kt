package app.lifeos.next.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.theme.LifeOsTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LifeOsHubSheet(
    selected: LifeOsDestination,
    attentionCount: Int,
    onSelect: (LifeOsDestination) -> Unit,
    onOpenSystem: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = LifeOsTokens.Layout.compactHorizontalPadding,
                    end = LifeOsTokens.Layout.compactHorizontalPadding,
                    bottom = LifeOsTokens.Spacing.xLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Text("Bereiche", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Nur Arbeitsflächen, die eine echte LIFEOS-Fähigkeit bündeln.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = LifeOsTokens.Spacing.small),
            )

            LifeOsDestination.ordered.forEach { destination ->
                HubRow(
                    title = destination.label,
                    body = hubDescription(destination),
                    selected = selected == destination,
                    onClick = { onSelect(destination) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = LifeOsTokens.Spacing.small)
            )

            HubRow(
                title = if (attentionCount > 0) {
                    "System · $attentionCount"
                } else {
                    "System"
                },
                body = "Status, Freigaben, Werkzeuge und Wartung.",
                selected = false,
                onClick = onOpenSystem,
            )
        }
    }
}

@Composable
private fun HubRow(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.background
        },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = LifeOsTokens.Spacing.large,
                vertical = LifeOsTokens.Spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hubDescription(destination: LifeOsDestination): String = when (destination) {
    LifeOsDestination.CHAT -> "Haupthub für Fragen, Planung, Erstellung und Aktionen."
    LifeOsDestination.PROJECTS -> "Laufende Vorhaben, Schritte, Fortschritt und Blockaden."
    LifeOsDestination.WEEK -> "Kalender und Projekttermine in einer Wochenansicht."
    LifeOsDestination.ACTIONS -> "Offene Rückfragen und direkt anstoßbare Owner-Schritte."
    LifeOsDestination.ARTIFACTS -> "Generierte Bilder, Texte, Dokumente und Berichte."
}
