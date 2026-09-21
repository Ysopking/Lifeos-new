package app.lifeos.next.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.PersonalConversationImportViewModel
import app.lifeos.next.R
import app.lifeos.next.StorageMaintenanceViewModel
import app.lifeos.next.ui.assets.OwnerAssetReviewScreen
import app.lifeos.next.ui.decision.LifeOsDecisionTraceScreen
import app.lifeos.next.ui.layout.LifeOsDetailHost
import app.lifeos.next.ui.theme.LifeOsTokens
import app.lifeos.next.ui.tools.LifeOsToolCenterScreen

private enum class SystemHubPage {
    WHY,
    TOOLS,
    ASSETS,
    STORAGE,
    PERSONAL_DATA,
    RUNTIME,
}

@Composable
fun LifeOsSystemHub(
    model: LifeOsChatViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    storageMaintenanceModel: StorageMaintenanceViewModel,
    personalConversationImportModel: PersonalConversationImportViewModel,
    ownerAttention: OwnerAttentionUiState,
    modifier: Modifier = Modifier,
) {
    var selectedPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPage = selectedPageName?.let { raw ->
        runCatching { SystemHubPage.valueOf(raw) }.getOrNull()
    }

    BackHandler(enabled = selectedPage != null) {
        selectedPageName = null
    }

    LifeOsDetailHost(
        detailVisible = selectedPage != null,
        modifier = modifier.fillMaxSize(),
        mainContent = { contentModifier ->
            LifeOsSystemOverview(
                model = model,
                ownerAttention = ownerAttention,
                onOpenStorage = { selectedPageName = SystemHubPage.STORAGE.name },
                onOpenPersonalData = { selectedPageName = SystemHubPage.PERSONAL_DATA.name },
                onOpenAssets = { selectedPageName = SystemHubPage.ASSETS.name },
                onOpenTools = { selectedPageName = SystemHubPage.TOOLS.name },
                onOpenWhy = { selectedPageName = SystemHubPage.WHY.name },
                onOpenRuntime = { selectedPageName = SystemHubPage.RUNTIME.name },
                modifier = contentModifier,
            )
        },
        detailContent = { contentModifier ->
            when (selectedPage) {
                SystemHubPage.WHY -> SystemDetailPage(
                    title = "Warum",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    LifeOsDecisionTraceScreen(decisionTraceModel, detailModifier)
                }

                SystemHubPage.TOOLS -> SystemDetailPage(
                    title = "Tools",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    LifeOsToolCenterScreen(toolCenterModel, detailModifier)
                }

                SystemHubPage.ASSETS -> SystemDetailPage(
                    title = "Freigaben",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    OwnerAssetReviewScreen(assetReviewModel, detailModifier)
                }

                SystemHubPage.STORAGE -> SystemDetailPage(
                    title = "Speicher",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    StorageMaintenanceScreen(storageMaintenanceModel, detailModifier)
                }

                SystemHubPage.PERSONAL_DATA -> SystemDetailPage(
                    title = "Persönliche Gespräche",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    PersonalConversationImportScreen(
                        model = personalConversationImportModel,
                        modifier = detailModifier,
                    )
                }

                SystemHubPage.RUNTIME -> SystemDetailPage(
                    title = "Runtime-Diagnose",
                    onBack = { selectedPageName = null },
                    modifier = contentModifier,
                ) { detailModifier ->
                    SystemRuntimeHealthScreen(
                        model = model,
                        modifier = detailModifier,
                    )
                }

                null -> Unit
            }
        },
    )
}

@Composable
private fun SystemDetailPage(
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "Zurück zu System"
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = null,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(LifeOsTokens.Size.minimumTouchTarget))
        }
        content(Modifier.fillMaxSize())
    }
}
