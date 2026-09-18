package app.lifeos.next.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.ui.assets.OwnerAssetReviewScreen
import app.lifeos.next.ui.components.LifeOsScreenHeader
import app.lifeos.next.ui.decision.LifeOsDecisionTraceScreen
import app.lifeos.next.ui.theme.LifeOsTokens
import app.lifeos.next.ui.tools.LifeOsToolCenterScreen

private enum class SystemHubPage {
    OVERVIEW,
    WHY,
    TOOLS,
    ASSETS,
}

@Composable
fun LifeOsSystemHub(
    model: LifeOsChatViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SystemHubPage.OVERVIEW) }

    BackHandler(enabled = page != SystemHubPage.OVERVIEW) {
        page = SystemHubPage.OVERVIEW
    }

    when (page) {
        SystemHubPage.OVERVIEW -> SystemOverview(
            model = model,
            onWhy = { page = SystemHubPage.WHY },
            onTools = { page = SystemHubPage.TOOLS },
            onAssets = { page = SystemHubPage.ASSETS },
            modifier = modifier,
        )
        SystemHubPage.WHY -> TechnicalSubpage(
            title = "Warum",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            LifeOsDecisionTraceScreen(decisionTraceModel, contentModifier)
        }
        SystemHubPage.TOOLS -> TechnicalSubpage(
            title = "Tools",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            LifeOsToolCenterScreen(toolCenterModel, contentModifier)
        }
        SystemHubPage.ASSETS -> TechnicalSubpage(
            title = "Assets",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            OwnerAssetReviewScreen(assetReviewModel, contentModifier)
        }
    }
}

@Composable
private fun SystemOverview(
    model: LifeOsChatViewModel,
    onWhy: () -> Unit,
    onTools: () -> Unit,
    onAssets: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = LifeOsTokens.Layout.contentMaxWidth)
                .padding(
                    horizontal = LifeOsTokens.Spacing.large,
                    vertical = LifeOsTokens.Spacing.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
        ) {
            LifeOsScreenHeader(
                title = "System",
                subtitle = "Runtime-Gesundheit, Nachvollziehbarkeit und kontrollierte Erweiterungen.",
                eyebrow = "Owner Console",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                SystemEntryCard(
                    title = "Warum",
                    subtitle = "DecisionTrace & Evidenz",
                    glyph = "?",
                    onClick = onWhy,
                    modifier = Modifier.weight(1f),
                )
                SystemEntryCard(
                    title = "Tools",
                    subtitle = "Fähigkeiten & Trials",
                    glyph = "⚙",
                    onClick = onTools,
                    modifier = Modifier.weight(1f),
                )
                SystemEntryCard(
                    title = "Assets",
                    subtitle = "Artefakte & Reviews",
                    glyph = "▣",
                    onClick = onAssets,
                    modifier = Modifier.weight(1f),
                )
            }
            SystemRuntimeHealthScreen(
                model = model,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SystemEntryCard(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = LifeOsTokens.Elevation.resting,
    ) {
        Column(
            modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TechnicalSubpage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = LifeOsTokens.Spacing.small,
                    vertical = LifeOsTokens.Spacing.xSmall,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text(title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {}) { Text("") }
        }
        content(Modifier.fillMaxSize())
    }
}
