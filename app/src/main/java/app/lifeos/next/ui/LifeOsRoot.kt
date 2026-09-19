package app.lifeos.next.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsGoalsViewModel
import app.lifeos.next.LifeOsMemoryViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.StorageMaintenanceViewModel
import app.lifeos.next.ui.assets.OwnerAssetReviewScreen
import app.lifeos.next.ui.chat.LifeOsChatScreen
import app.lifeos.next.ui.decision.LifeOsDecisionTraceScreen
import app.lifeos.next.ui.goals.LifeOsGoalsScreen
import app.lifeos.next.ui.layout.AdaptiveLifeOsScaffold
import app.lifeos.next.ui.memory.LifeOsMemoryScreen
import app.lifeos.next.ui.system.LifeOsSystemHub
import app.lifeos.next.ui.theme.LifeOsTheme
import app.lifeos.next.ui.tools.LifeOsToolCenterScreen

@Composable
fun LifeOsRoot(
    model: LifeOsChatViewModel,
    memoryModel: LifeOsMemoryViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    goalsModel: LifeOsGoalsViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    storageMaintenanceModel: StorageMaintenanceViewModel,
    onRequestMicrophonePermission: () -> Unit = {},
) {
    var selectedKey by rememberSaveable {
        mutableStateOf(LifeOsDestination.default.key)
    }
    val selected = LifeOsDestination.fromKey(selectedKey)

    BackHandler(enabled = selected != LifeOsDestination.CHAT) {
        selectedKey = LifeOsDestination.CHAT.key
    }

    LifeOsTheme {
        AdaptiveLifeOsScaffold(
            selected = selected,
            onSelect = { destination -> selectedKey = destination.key },
        ) { contentModifier ->
            when (selected) {
                LifeOsDestination.CHAT -> LifeOsChatScreen(
                    model = model,
                    modifier = contentModifier,
                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                )
                LifeOsDestination.MEMORY -> LifeOsMemoryScreen(
                    model = memoryModel,
                    modifier = contentModifier,
                )
                LifeOsDestination.ASSETS -> OwnerAssetReviewScreen(
                    model = assetReviewModel,
                    modifier = contentModifier,
                )
                LifeOsDestination.GOALS -> LifeOsGoalsScreen(
                    model = goalsModel,
                    modifier = contentModifier,
                )
                LifeOsDestination.WHY -> LifeOsDecisionTraceScreen(
                    model = decisionTraceModel,
                    modifier = contentModifier,
                )
                LifeOsDestination.TOOLS -> LifeOsToolCenterScreen(
                    model = toolCenterModel,
                    modifier = contentModifier,
                )
                LifeOsDestination.SYSTEM -> LifeOsSystemHub(
                    model = model,
                    decisionTraceModel = decisionTraceModel,
                    toolCenterModel = toolCenterModel,
                    assetReviewModel = assetReviewModel,
                    storageMaintenanceModel = storageMaintenanceModel,
                    modifier = contentModifier,
                )
            }
        }
    }
}
