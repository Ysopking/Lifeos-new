package app.lifeos.next.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsGoalsViewModel
import app.lifeos.next.LifeOsMemoryViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.PersonalConversationImportViewModel
import app.lifeos.next.StorageMaintenanceViewModel
import app.lifeos.next.ui.chat.LifeOsChatScreen
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.goals.LifeOsGoalsScreen
import app.lifeos.next.ui.layout.AdaptiveLifeOsScaffold
import app.lifeos.next.ui.memory.LifeOsMemoryScreen
import app.lifeos.next.ui.system.LifeOsSystemOverlay
import app.lifeos.next.ui.system.OwnerAttentionProjector
import app.lifeos.next.ui.theme.LifeOsTheme

@Composable
fun LifeOsRoot(
    model: LifeOsChatViewModel,
    memoryModel: LifeOsMemoryViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    goalsModel: LifeOsGoalsViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    storageMaintenanceModel: StorageMaintenanceViewModel,
    personalConversationImportModel: PersonalConversationImportViewModel,
    onRequestMicrophonePermission: () -> Unit = {},
) {
    var selectedKey by rememberSaveable {
        mutableStateOf(LifeOsDestination.default.key)
    }
    var showSystem by rememberSaveable {
        mutableStateOf(false)
    }
    val selected = LifeOsDestination.fromKey(selectedKey)
    val chatState by model.state.collectAsStateWithLifecycle()
    val assetState by assetReviewModel.state.collectAsStateWithLifecycle()
    val toolState by toolCenterModel.state.collectAsStateWithLifecycle()
    val runtimeHealth = buildRuntimeHealthUiModel(
        bootStatus = chatState.bootStatus,
        readiness = chatState.readiness,
        topologyEvidence = chatState.runtimeTopology,
        selfStateEvidence = chatState.selfState,
    )
    val ownerAttention = OwnerAttentionProjector.project(
        assets = assetState,
        tools = toolState,
        runtimeLevel = runtimeHealth.level,
    )

    LaunchedEffect(toolCenterModel) {
        toolCenterModel.refresh()
    }

    BackHandler(enabled = showSystem || selected != LifeOsDestination.CHAT) {
        if (showSystem) {
            showSystem = false
        } else {
            selectedKey = LifeOsDestination.CHAT.key
        }
    }

    LifeOsTheme {
        AdaptiveLifeOsScaffold(
            selected = selected,
            onSelect = { destination -> selectedKey = destination.key },
            onOpenSystem = { showSystem = true },
            attentionCount = ownerAttention.totalCount,
        ) { contentModifier ->
            when (selected) {
                LifeOsDestination.CHAT -> LifeOsChatScreen(
                    model = model,
                    modifier = contentModifier,
                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                )

                LifeOsDestination.GOALS -> LifeOsGoalsScreen(
                    model = goalsModel,
                    modifier = contentModifier,
                )

                LifeOsDestination.MEMORY -> LifeOsMemoryScreen(
                    model = memoryModel,
                    modifier = contentModifier,
                )
            }
        }

        if (showSystem) {
            LifeOsSystemOverlay(
                model = model,
                decisionTraceModel = decisionTraceModel,
                toolCenterModel = toolCenterModel,
                assetReviewModel = assetReviewModel,
                storageMaintenanceModel = storageMaintenanceModel,
                personalConversationImportModel = personalConversationImportModel,
                ownerAttention = ownerAttention,
                onDismiss = { showSystem = false },
            )
        }
    }
}
