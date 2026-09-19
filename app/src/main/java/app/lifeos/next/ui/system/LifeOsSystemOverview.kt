package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
internal fun LifeOsSystemOverview(
    model: LifeOsChatViewModel,
    ownerAttention: OwnerAttentionUiState,
    onOpenStorage: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenWhy: () -> Unit,
    onOpenRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val health = buildRuntimeHealthUiModel(
        bootStatus = state.bootStatus,
        readiness = state.readiness,
        topologyEvidence = state.runtimeTopology,
        selfStateEvidence = state.selfState,
    )

    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LifeOsTokens.Spacing.large),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Text(
                text = "LIFEOS",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (ownerAttention.hasAttention) {
                    "${ownerAttention.totalCount} Punkte brauchen deine Aufmerksamkeit"
                } else {
                    health.compactLabel
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SystemSectionLabel("Daten & Zugriff")
        SystemNavigationRow(
            label = "Speicher",
            supporting = "Dateien, Bereinigung und LIFEOS-Papierkorb",
            onClick = onOpenStorage,
        )
        SystemNavigationRow(
            label = "Freigaben",
            supporting = if (ownerAttention.pendingAssetReviews > 0) {
                "${ownerAttention.pendingAssetReviews} offene Freigaben warten auf deine Entscheidung"
            } else {
                "Assets prüfen und Owner-Entscheidungen treffen"
            },
            attentionCount = ownerAttention.pendingAssetReviews,
            onClick = onOpenAssets,
        )

        HorizontalDivider()

        SystemSectionLabel("Kontrolle")
        SystemNavigationRow(
            label = "Tools",
            supporting = if (ownerAttention.toolActions > 0) {
                "${ownerAttention.toolActions} Tool-Entscheidungen brauchen dich"
            } else {
                "Generierte Tools und ihr Lifecycle"
            },
            attentionCount = ownerAttention.toolActions,
            onClick = onOpenTools,
        )
        SystemNavigationRow(
            label = "Warum / Entscheidungen",
            supporting = "Decision Trace und nachvollziehbare Gründe",
            onClick = onOpenWhy,
        )

        HorizontalDivider()

        SystemSectionLabel("Erweitert")
        SystemNavigationRow(
            label = "Runtime-Diagnose",
            supporting = health.summary,
            attentionCount = if (ownerAttention.runtimeNeedsAttention) 1 else 0,
            onClick = onOpenRuntime,
        )
    }
}

@Composable
private fun SystemSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            top = LifeOsTokens.Spacing.large,
            bottom = LifeOsTokens.Spacing.xSmall,
        ),
    )
}

@Composable
private fun SystemNavigationRow(
    label: String,
    supporting: String,
    onClick: () -> Unit,
    attentionCount: Int = 0,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = LifeOsTokens.Spacing.large,
                vertical = LifeOsTokens.Spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (attentionCount > 0) {
                    Text(
                        text = attentionCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
