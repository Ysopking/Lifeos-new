package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.buildReadinessUiModel
import app.lifeos.next.ui.components.RuntimeHealthUiModel
import app.lifeos.next.ui.components.RuntimeTopologyUiEvidence
import app.lifeos.next.ui.components.SelfStateUiEvidence
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeHealthModalSheet(
    health: RuntimeHealthUiModel,
    readiness: LifeOsReadinessSnapshot?,
    topology: RuntimeTopologyUiEvidence?,
    onDismiss: () -> Unit,
    selfState: SelfStateUiEvidence? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RuntimeHealthContent(
            health = health,
            readiness = readiness,
            topology = topology,
            modifier = Modifier.fillMaxWidth(),
            selfState = selfState,
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
        selfStateEvidence = state.selfState,
    )
    RuntimeHealthContent(
        health = health,
        readiness = state.readiness,
        topology = state.runtimeTopology,
        showWebDeepSearchControl = true,
        modifier = modifier.fillMaxSize(),
        selfState = state.selfState,
    )
}

@Composable
fun RuntimeHealthContent(
    health: RuntimeHealthUiModel,
    readiness: LifeOsReadinessSnapshot?,
    topology: RuntimeTopologyUiEvidence?,
    modifier: Modifier = Modifier,
    showWebDeepSearchControl: Boolean = false,
    selfState: SelfStateUiEvidence? = null,
) {
    var showReadinessBlocks by rememberSaveable { mutableStateOf(false) }
    val readinessModel = readiness?.let(::buildReadinessUiModel)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("System", style = MaterialTheme.typography.headlineMedium)
        Text(health.compactLabel, style = MaterialTheme.typography.titleLarge)
        Text(health.summary, style = MaterialTheme.typography.bodyMedium)

        if (showWebDeepSearchControl) {
            Spacer(Modifier.height(4.dp))
            WebDeepSearchOwnerControl()
        }

        Spacer(Modifier.height(4.dp))
        Text("Boot", style = MaterialTheme.typography.titleMedium)
        Text(health.bootLabel, style = MaterialTheme.typography.bodyMedium)

        Text("Runtime-Topologie", style = MaterialTheme.typography.titleMedium)
        Text(health.topologySummary, style = MaterialTheme.typography.bodyMedium)
        topology?.takeIf { it.observed }?.let { evidence ->
            Text(
                "${evidence.capabilityProviders} Provider · ${evidence.generatedProviders} generiert",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("SELF STATE", style = MaterialTheme.typography.titleMedium)
        if (selfState == null) {
            Text("Noch keine verifizierte Self-State-Evidenz verfügbar.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Authority — " + selfState.authorityFingerprintShort, style = MaterialTheme.typography.bodySmall)
            Text(
                "World — " + (selfState.worldRevision?.let { "Revision " + it } ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Equation — " + (selfState.equationVersion ?: "nicht beobachtet"), style = MaterialTheme.typography.bodySmall)
            Text(
                "PhotonStore — " + (selfState.photonCount?.let { it.toString() + " Photonen" } ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Memory — " + (selfState.memoryNodeCount?.let { it.toString() + " Knoten" } ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Runtime — " + (
                    topology?.takeIf { it.observed }?.let {
                        it.operationalSubsystems.toString() + "/" + it.registeredSubsystems + " operational"
                    } ?: "nicht beobachtet"
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Resources — " + (
                    selfState.resourceCapabilityReadiness?.let {
                        "Readiness " + "%.0f".format(it * 100.0) + " %"
                    } ?: "nicht beobachtet"
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Health — " + if (
                    selfState.healthyNodes != null &&
                    selfState.degradedNodes != null &&
                    selfState.unknownHealthNodes != null
                ) {
                    selfState.healthyNodes.toString() + " gesund · " +
                        selfState.degradedNodes + " eingeschränkt · " +
                        selfState.unknownHealthNodes + " unbekannt"
                } else {
                    "nicht beobachtet"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Recovery — " + (selfState.activeRepairs?.let { it.toString() + " aktiv" } ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Tools — " + (selfState.activeTools?.let { it.toString() + " aktiv" } ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Live Sources — " + (selfState.liveSources?.toString() ?: "nicht beobachtet"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("WorldFormula band — " + selfState.observationBand, style = MaterialTheme.typography.bodySmall)
        }

        Text("A–P Readiness", style = MaterialTheme.typography.titleMedium)
        Text(health.readinessSummary, style = MaterialTheme.typography.bodyMedium)
        if (readinessModel != null) {
            TextButton(onClick = { showReadinessBlocks = !showReadinessBlocks }) {
                Text(if (showReadinessBlocks) "A–P-Details ausblenden" else "A–P-Details anzeigen")
            }
            if (showReadinessBlocks) {
                readinessModel.rows.forEach { (block, status) ->
                    Text(
                        "Block $block — $status",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Text("Noch keine A–P-Readiness-Evidenz verfügbar.", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))
        Text("Technische Evidenz", style = MaterialTheme.typography.titleMedium)
        Text(
            "Topologie beobachtet: ${if (topology?.observed == true) "ja" else "nein"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Readiness-Fingerprint: ${readiness?.fingerprint ?: "ausstehend"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Diese Ansicht zeigt Runtime-Evidenz und leitet daraus keinen CI-, Release- oder Product-Gold-Status ab.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
    }
}
