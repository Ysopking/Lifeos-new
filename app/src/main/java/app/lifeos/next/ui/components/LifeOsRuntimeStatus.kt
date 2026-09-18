package app.lifeos.next.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.ReadinessState
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.theme.LifeOsTokens

enum class RuntimeHealthLevel { STARTING, VERIFYING, READY, DEGRADED, FAILED }

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
        ready.toString() + " bereit · " + degraded + " eingeschränkt · " + blocked + " blockiert"
    } ?: "A–P-Evidenz ausstehend"

    val topologyObserved = topologyEvidence?.observed == true
    val topologySummary = if (!topologyObserved) {
        "Topologie-Evidenz ausstehend"
    } else {
        val topology = requireNotNull(topologyEvidence)
        topology.operationalSubsystems.toString() + "/" + topology.registeredSubsystems +
            " Subsysteme operational · " + topology.unavailableSubsystems +
            " nicht verfügbar · " + topology.unboundSubsystems +
            " ungebunden · " + topology.degradedSubsystems + " eingeschränkt"
    }

    val evidenceMissing = readiness == null || !topologyObserved
    val readinessDegraded = readiness?.let { snapshot ->
        !snapshot.complete || snapshot.blocks.any { it.state != ReadinessState.READY }
    } ?: false
    val topologyDegraded = topologyEvidence?.takeIf { it.observed }?.let { topology ->
        !topology.fullyConnected || !topology.fullyOperational ||
            topology.unavailableSubsystems > 0 || topology.unboundSubsystems > 0 ||
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
    val colors = runtimeStatusColors(model.level)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
            .clickable(onClick = onOpenDetails),
        color = colors.container,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = LifeOsTokens.Spacing.medium,
                vertical = LifeOsTokens.Spacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(colors.dot, CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.compactLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.content.copy(alpha = 0.82f),
                )
            }
            Text("Details", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private data class RuntimeStatusColors(
    val container: Color,
    val content: Color,
    val dot: Color,
)

@Composable
private fun runtimeStatusColors(level: RuntimeHealthLevel): RuntimeStatusColors = when (level) {
    RuntimeHealthLevel.READY -> RuntimeStatusColors(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.primary,
    )
    RuntimeHealthLevel.DEGRADED -> RuntimeStatusColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.tertiary,
    )
    RuntimeHealthLevel.FAILED -> RuntimeStatusColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
        MaterialTheme.colorScheme.error,
    )
    RuntimeHealthLevel.STARTING,
    RuntimeHealthLevel.VERIFYING -> RuntimeStatusColors(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.secondary,
    )
}
