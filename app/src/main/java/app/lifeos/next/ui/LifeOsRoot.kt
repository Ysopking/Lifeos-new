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
import app.lifeos.next.ui.actions.LifeOsActionCenterScreen
import app.lifeos.next.ui.assets.OwnerAssetReviewScreen
import app.lifeos.next.ui.calendar.LifeOsWeekScreen
import app.lifeos.next.ui.chat.LifeOsChatScreen
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.goals.LifeOsProjectsScreen
import app.lifeos.next.ui.layout.AdaptiveLifeOsScaffold
import app.lifeos.next.ui.speech.LifeOsSpokenOutputEffect
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
    @Suppress("UNUSED_VARIABLE")
    val retainedMemoryRuntime = memoryModel

    var selectedKey by rememberSaveable {
        mutableStateOf(LifeOsDestination.default.key)
    }
    var showHub by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }

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

    fun submitFromWorkspace(prompt: String) {
        model.editDraft(prompt)
        model.sendMessage()
        selectedKey = LifeOsDestination.CHAT.key
    }

    BackHandler(
        enabled = showSystem || showHub || selected != LifeOsDestination.CHAT
    ) {
        when {
            showSystem -> showSystem = false
            showHub -> showHub = false
            else -> selectedKey = LifeOsDestination.CHAT.key
        }
    }

    LifeOsTheme {
        LifeOsSpokenOutputEffect(
            timeline = chatState.timeline,
            bootStatus = chatState.bootStatus,
            voicePhase = chatState.voice.phase,
        )

        AdaptiveLifeOsScaffold(
            selected = selected,
            onOpenHub = { showHub = true },
            onBackToChat = { selectedKey = LifeOsDestination.CHAT.key },
            attentionCount = ownerAttention.totalCount,
        ) { contentModifier ->
            when (selected) {
                LifeOsDestination.CHAT -> LifeOsChatScreen(
                    model = model,
                    modifier = contentModifier,
                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                )

                LifeOsDestination.PROJECTS -> LifeOsProjectsScreen(
                    model = goalsModel,
                    modifier = contentModifier,
                )

                LifeOsDestination.WEEK -> LifeOsWeekScreen(
                    goalsModel = goalsModel,
                    onOpenProject = { planId ->
                        goalsModel.selectPlan(planId)
                        selectedKey = LifeOsDestination.PROJECTS.key
                    },
                    modifier = contentModifier,
                )

                LifeOsDestination.ACTIONS -> LifeOsActionCenterScreen(
                    chatModel = model,
                    goalsModel = goalsModel,
                    onSubmitPrompt = ::submitFromWorkspace,
                    modifier = contentModifier,
                )

                LifeOsDestination.ARTIFACTS -> OwnerAssetReviewScreen(
                    model = assetReviewModel,
                    modifier = contentModifier,
                )
            }
        }

        if (showHub) {
            LifeOsHubSheet(
                selected = selected,
                attentionCount = ownerAttention.totalCount,
                onSelect = { destination ->
                    selectedKey = destination.key
                    showHub = false
                },
                onOpenSystem = {
                    showHub = false
                    showSystem = true
                },
                onDismiss = { showHub = false },
            )
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
