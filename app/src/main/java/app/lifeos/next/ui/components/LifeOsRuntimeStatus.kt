package app.lifeos.next.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.ReadinessState
import app.lifeos.next.kernel.KernelBootstrapStatus

enum class RuntimeHealthLevel {
    STARTING,
    VERIFYING,
    READY,
    DEGRADED,
    FAILED,
}

data class RuntimeTopologyUiEvidence(
    val observed: Boolean,
    val registeredSubsystems: Int,
    val operationalSubsystems: Int,
    val unavailableSubsystems: Int,
    val unboundSubsystems: Int,
    val degradedSubsystems: Int,
    val capabilityProviders: Int,
    val generatedProviders: Int,
    val fullyConnected: Boolean,
    val fullyOperational: Boolean,
) {
    init {
        require(registeredSubsystems >= 0)
        require(operationalSubsystems >= 0)
        require(unavailableSubsystems >= 0)
        require(unboundSubsystems >= 0)
        require(degradedSubsystems >= 0)
        require(capabilityProviders >= 0)
        require(generatedProviders >= 0)
        require(operationalSubsystems <= registeredSubsystems)
    }
}

data class RuntimeHealthUiModel(
    val level: RuntimeHealthLevel,
    val compactLabel: String,
    val summary: String,
    val bootLabel: String,
    val readinessSummary: String,
    val topologySummary: String,
    val detailsAvailable: Boolean,
)

fun buildRuntimeHealthUiModel(
    bootStatus: KernelBootstrapStatus,
    readiness: LifeOsReadinessSnapshot?,
    topologyEvidence: RuntimeTopologyUiEvidence?,
): RuntimeHealthUiModel {
    val bootLabel = when (bootStatus) {
        KernelBootstrapStatus.CREATED -> "Start"
        KernelBootstrapStatus.LOADING -> "Gedächtnis wird geladen"
        KernelBootstrapStatus.READY -> "Bereit"
        KernelBootstrapStatus.DEGRADED -> "Eingeschränkt"
        KernelBootstrapStatus.FAILED -> "Fehler"
    }

    val readinessSummary = readiness?.let { snapshot ->
        val ready = snapshot.blocks.count { it.state == ReadinessState.READY }
        val degraded = snapshot.blocks.count { it.state == ReadinessState.DEGRADED }
        val blocked = snapshot.blocks.count { it.state == ReadinessState.BLOCKED }
        "$ready bereit · $degraded eingeschränkt · $blocked blockiert"
    } ?: "A–P-Evidenz ausstehend"

    val topologyObserved = topologyEvidence?.observed == true
    val topologySummary = if (!topologyObserved) {
        "Topologie-Evidenz ausstehend"
    } else {
        val topology = requireNotNull(topologyEvidence)
        "${topology.operationalSubsystems}/${topology.registeredSubsystems} Subsysteme operational · " +
            "${topology.unavailableSubsystems} nicht verfügbar · ${topology.unboundSubsystems} ungebunden · " +
            "${topology.degradedSubsystems} eingeschränkt"
    }

    val evidenceMissing = readiness == null || !topologyObserved
    val readinessDegraded = readiness?.let { snapshot ->
        !snapshot.complete || snapshot.blocks.any { it.state != ReadinessState.READY }
    } ?: false
    val topologyDegraded = topologyEvidence?.takeIf { it.observed }?.let { topology ->
        !topology.fullyConnected ||
            !topology.fullyOperational ||
            topology.unavailableSubsystems > 0 ||
            topology.unboundSubsystems > 0 ||
            topology.degradedSubsystems > 0
    } ?: false

    val level = when {
        bootStatus == KernelBootstrapStatus.FAILED -> RuntimeHealthLevel.FAILED
        bootStatus == KernelBootstrapStatus.CREATED || bootStatus == KernelBootstrapStatus.LOADING ->
            RuntimeHealthLevel.STARTING
        evidenceMissing -> RuntimeHealthLevel.VERIFYING
        bootStatus == KernelBootstrapStatus.DEGRADED || readinessDegraded || topologyDegraded ->
            RuntimeHealthLevel.DEGRADED
        bootStatus == KernelBootstrapStatus.READY &&
            readiness?.complete == true &&
            topologyEvidence?.observed == true &&
            topologyEvidence.fullyConnected &&
            topologyEvidence.fullyOperational -> RuntimeHealthLevel.READY
        else -> RuntimeHealthLevel.VERIFYING
    }

    val compactLabel = when (level) {
        RuntimeHealthLevel.STARTING -> "LIFEOS startet"
        RuntimeHealthLevel.VERIFYING -> "Runtime wird geprüft"
        RuntimeHealthLevel.READY -> "Runtime bereit"
        RuntimeHealthLevel.DEGRADED -> "Runtime eingeschränkt"
        RuntimeHealthLevel.FAILED -> "Runtimefehler"
    }

    val summary = when (level) {
        RuntimeHealthLevel.STARTING -> "LIFEOS lädt die lokale Runtime-Evidenz."
        RuntimeHealthLevel.VERIFYING -> "Boot ist verfügbar, aber Readiness oder Topologie ist noch nicht vollständig belegt."
        RuntimeHealthLevel.READY -> "Boot, A–P-Readiness und Runtime-Topologie sind vollständig bereit."
        RuntimeHealthLevel.DEGRADED -> when {
            bootStatus == KernelBootstrapStatus.DEGRADED -> "Der Kernel läuft eingeschränkt."
            readinessDegraded -> "Mindestens ein A–P-Block ist eingeschränkt oder blockiert."
            topologyDegraded -> "Mindestens ein Runtime-Subsystem ist eingeschränkt, ungebunden oder nicht verfügbar."
            else -> "Die Runtime ist nicht vollständig bereit."
        }
        RuntimeHealthLevel.FAILED -> "Der Kernel-Start ist fehlgeschlagen."
    }

    return RuntimeHealthUiModel(
        level = level,
        compactLabel = compactLabel,
        summary = summary,
        bootLabel = bootLabel,
        readinessSummary = readinessSummary,
        topologySummary = topologySummary,
        detailsAvailable = true,
    )
}

@Composable
fun LifeOsRuntimeStatus(
    model: RuntimeHealthUiModel,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        TextButton(
            onClick = onOpenDetails,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(model.compactLabel, style = MaterialTheme.typography.labelLarge)
                Text(model.summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
