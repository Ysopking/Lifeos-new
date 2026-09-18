package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.buildReadinessUiModel
import app.lifeos.next.ui.components.LifeOsPill
import app.lifeos.next.ui.components.RuntimeHealthUiModel
import app.lifeos.next.ui.components.RuntimeTopologyUiEvidence
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.theme.LifeOsTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeHealthModalSheet(
    health: RuntimeHealthUiModel,
    readiness: LifeOsReadinessSnapshot?,
    topology: RuntimeTopologyUiEvidence?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RuntimeHealthContent(
            health = health,
            readiness = readiness,
            topology = topology,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SystemRuntimeHealthScreen(
    model: LifeOsChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val health = buildRuntimeHealthUiModel(
        bootStatus = state.bootStatus,
        readiness = state.readiness,
        topologyEvidence = state.runtimeTopology,
    )
    RuntimeHealthContent(
        health = health,
        readiness = state.readiness,
        topology = state.runtimeTopology,
        showWebDeepSearchControl = true,
        showTitle = false,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
fun RuntimeHealthContent(
    health: RuntimeHealthUiModel,
    readiness: LifeOsReadinessSnapshot?,
    topology: RuntimeTopologyUiEvidence?,
    modifier: Modifier = Modifier,
    showWebDeepSearchControl: Boolean = false,
    showTitle: Boolean = true,
) {
    var showReadinessBlocks by rememberSaveable { mutableStateOf(false) }
    val readinessModel = readiness?.let(::buildReadinessUiModel)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = LifeOsTokens.Spacing.small),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
    ) {
        if (showTitle) {
            Text("System", style = MaterialTheme.typography.headlineMedium)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(LifeOsTokens.Spacing.large),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                Text(health.compactLabel, style = MaterialTheme.typography.titleLarge)
                Text(health.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showWebDeepSearchControl) {
            WebDeepSearchOwnerControl()
        }

        RuntimeMetricCard(
            title = "Runtime",
            value = health.bootLabel,
            detail = health.topologySummary,
            pill = topology?.takeIf { it.observed }?.let {
                it.operationalSubsystems.toString() + "/" + it.registeredSubsystems + " online"
            },
        )

        RuntimeMetricCard(
            title = "A–P Readiness",
            value = health.readinessSummary,
            detail = if (readinessModel == null) {
                "Noch keine A–P-Readiness-Evidenz verfügbar."
            } else {
                "Deterministische Readiness-Evidenz ist verfügbar."
            },
            pill = readiness?.takeIf { it.complete }?.let { "vollständig" },
        )

        if (readinessModel != null) {
            TextButton(onClick = { showReadinessBlocks = !showReadinessBlocks }) {
                Text(if (showReadinessBlocks) "A–P-Details ausblenden" else "A–P-Details anzeigen")
            }
            if (showReadinessBlocks) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = LifeOsTokens.Elevation.resting,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(LifeOsTokens.Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                    ) {
                        readinessModel.rows.forEach { (block, status) ->
                            Text(
                                "Block " + block + " — " + status,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                Text("Technische Evidenz", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Topologie beobachtet: " + if (topology?.observed == true) "ja" else "nein",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Readiness-Fingerprint: " + (readiness?.fingerprint ?: "ausstehend"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Diese Ansicht zeigt Runtime-Evidenz und leitet daraus keinen CI-, Release- oder Product-Gold-Status ab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(LifeOsTokens.Spacing.medium))
    }
}

@Composable
private fun RuntimeMetricCard(
    title: String,
    value: String,
    detail: String,
    pill: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = LifeOsTokens.Elevation.resting,
    ) {
        Row(
            modifier = Modifier.padding(LifeOsTokens.Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pill?.let { LifeOsPill(it) }
        }
    }
}
