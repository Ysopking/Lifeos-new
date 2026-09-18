package app.lifeos.next.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsToolCenterUiState
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.ui.components.LifeOsScreenHeader
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsToolCenterScreen(
    model: LifeOsToolCenterViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        model.refresh()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        ToolCenterOverview(
            state = state,
            onRefresh = model::refresh,
            onApproveGeneration = model::approveGeneration,
            onReviewAndActivate = model::reviewAndActivate,
            onDismissActionStatus = model::dismissActionStatus,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = LifeOsTokens.Layout.contentMaxWidth),
        )
    }
}

@Composable
private fun ToolCenterOverview(
    state: LifeOsToolCenterUiState,
    onRefresh: () -> Unit,
    onApproveGeneration: (String) -> Unit,
    onReviewAndActivate: (String) -> Unit,
    onDismissActionStatus: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LifeOsScreenHeader(
            title = "Tool Center",
            subtitle = "Fehlende Fähigkeiten, isolierte TRIALs und dauerhaft belegte Tool-Zustände.",
            eyebrow = "Capability Runtime",
            trailing = {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.loading && !state.actionInFlight,
                ) {
                    Text("Aktualisieren")
                }
            },
        )

        if (state.actionInFlight) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.actionStatus?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissActionStatus) { Text("Schließen") }
                }
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val workspace = state.workspace
        if (workspace == null && state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (workspace == null) {
            Text(
                "Der produktive Runtime-/Evidence-Stand konnte nicht geladen werden.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                OwnerSummaryCard(workspace)
            }

            item(key = "gaps-title") {
                SectionTitle(
                    "Fehlende Fähigkeiten",
                    "Eine Freigabe autorisiert genau einen begrenzten Genesis-Lauf. Sie aktiviert niemals ein Tool.",
                )
            }
            if (workspace.gaps.isEmpty()) {
                item(key = "gaps-empty") { EmptySection("Keine blockierenden Capability-Gaps aus dem aktuellen produktiven Routing.") }
            } else {
                items(workspace.gaps, key = { "gap:${it.capabilityId}" }) { gap ->
                    GapApprovalCard(
                        gap = gap,
                        actionRunning = state.generationInFlightCapabilityId == gap.capabilityId,
                        actionsBlocked = state.actionInFlight,
                        onApprove = { onApproveGeneration(gap.capabilityId) },
                    )
                }
            }

            item(key = "trial-divider") { HorizontalDivider() }
            item(key = "trial-title") {
                SectionTitle(
                    "TRIAL — getrennte Aktivierungsprüfung",
                    "Nur dauerhaft als TRIAL belegte Tools können die separate Owner-Prüfung starten.",
                )
            }
            if (workspace.trialTools.isEmpty()) {
                item(key = "trial-empty") { EmptySection("Keine dauerhaft belegten TRIAL-Tools.") }
            } else {
                items(workspace.trialTools, key = { "trial:${it.toolId}" }) { tool ->
                    OwnerToolCard(
                        tool = tool,
                        actionLabel = "Canaries prüfen & aktivieren",
                        actionRunning = state.activationInFlightToolId == tool.toolId,
                        actionEnabled = tool.activationEligible && !state.actionInFlight,
                        onAction = { onReviewAndActivate(tool.toolId) },
                    )
                }
            }

            item(key = "active-title") {
                SectionTitle(
                    "ACTIVE",
                    "Hier erscheinen ausschließlich Tools, deren dauerhafter Promotion-State ACTIVE meldet.",
                )
            }
            if (workspace.activeTools.isEmpty()) {
                item(key = "active-empty") { EmptySection("Keine dauerhaft als ACTIVE belegten generierten Tools.") }
            } else {
                items(workspace.activeTools, key = { "active:${it.toolId}" }) { OwnerToolCard(it) }
            }

            if (workspace.attentionTools.isNotEmpty()) {
                item(key = "attention-title") {
                    SectionTitle(
                        "Quarantäne / abgelehnt",
                        "Diese Tools bleiben sichtbar, werden aber niemals als verfügbar oder aktivierbar dargestellt.",
                    )
                }
                items(workspace.attentionTools, key = { "attention:${it.toolId}" }) { OwnerToolCard(it) }
            }

            if (workspace.pendingTools.isNotEmpty()) {
                item(key = "pending-title") {
                    SectionTitle(
                        "In Vorbereitung",
                        "Generiert, gebaut, getestet, verifiziert oder noch nicht dauerhaft belegbar — ohne ACTIVE-Claim.",
                    )
                }
                items(workspace.pendingTools, key = { "pending:${it.toolId}" }) { OwnerToolCard(it) }
            }

            if (workspace.retiredTools.isNotEmpty()) {
                item(key = "retired-title") { SectionTitle("Stillgelegt", "Retired Tools bleiben nachvollziehbar sichtbar.") }
                items(workspace.retiredTools, key = { "retired:${it.toolId}" }) { OwnerToolCard(it) }
            }

            item(key = "providers-divider") { HorizontalDivider() }
            item(key = "providers-title") {
                SectionTitle(
                    "Capability Provider",
                    "Read-only Diagnose des produktiven Registry; deaktivierte und quarantänisierte Provider bleiben sichtbar.",
                )
            }
            if (workspace.runtime.providers.isEmpty()) {
                item(key = "providers-empty") { EmptySection("Aktuell sind keine Capability Provider sichtbar.") }
            } else {
                items(
                    workspace.runtime.providers,
                    key = { "provider:${it.capabilityId}:${it.providerId}" },
                ) { provider -> ProviderCard(provider) }
            }
        }
    }
}

@Composable
private fun OwnerSummaryCard(workspace: ToolCenterOwnerWorkspaceUiModel) {
    val summary = workspace.runtime.summary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(summary.runtimeLabel, style = MaterialTheme.typography.titleMedium, color = toneColor(summary.runtimeTone))
            Text("${summary.capabilityCount} Fähigkeiten · ${summary.providerCount} Provider", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Dauerhaft: ACTIVE ${workspace.activeTools.size} · TRIAL ${workspace.trialTools.size} · Aufmerksamkeit ${workspace.attentionTools.size} · Vorbereitung ${workspace.pendingTools.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GapApprovalCard(
    gap: ToolCenterGapUiModel,
    actionRunning: Boolean,
    actionsBlocked: Boolean,
    onApprove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(gap.capabilityId, style = MaterialTheme.typography.titleSmall)
            Text("${gap.gapTypeLabel} · ${gap.severityLabel}", style = MaterialTheme.typography.bodySmall)
            DetailLine("Benötigte Inputs", gap.requiredInputs)
            DetailLine("Benötigte Outputs", gap.requiredOutputs)
            DetailLine("Kandidaten", gap.candidateProviderIds)
            Text(
                "Owner-Freigabe erlaubt nur den begrenzten Genesis-/Build-/Verify-Pfad. ACTIVE erfordert später eine separate TRIAL-Prüfung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onApprove,
                enabled = gap.approvalEligible && !actionsBlocked,
            ) {
                Text(if (actionRunning) "Wird erzeugt …" else "Tool-Erzeugung freigeben")
            }
        }
    }
}

@Composable
private fun OwnerToolCard(
    tool: ToolCenterOwnerToolUiModel,
    actionLabel: String? = null,
    actionRunning: Boolean = false,
    actionEnabled: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    var evidenceExpanded by rememberSaveable(tool.toolId) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tool.toolId, style = MaterialTheme.typography.titleSmall)
                Text(tool.stateLabel, style = MaterialTheme.typography.labelLarge, color = toneColor(tool.tone))
            }
            Text("Fähigkeit: ${tool.capabilityId} · Verifikation ${tool.verificationPercent}%", style = MaterialTheme.typography.bodySmall)
            Text(
                "Trials ${tool.trials} · Erfolge ${tool.successes} · Safety-Verstöße ${tool.safetyViolations}",
                style = MaterialTheme.typography.bodySmall,
            )
            DetailLine("Berechtigungen", tool.permissions)
            DetailLine("Inputs", tool.requiredInputs)
            DetailLine("Outputs", tool.requiredOutputs)
            tool.lastMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val evidenceIds = listOfNotNull(
                tool.promotionEvidenceId?.let { "Promotion: $it" },
                tool.boundedAdmissionEvidenceId?.let { "Admission: $it" },
                tool.boundedReadinessEvidenceId?.let { "Readiness: $it" },
                tool.boundedPromotionSealId?.let { "Promotion-Seal: $it" },
            )
            if (evidenceIds.isNotEmpty()) {
                TextButton(onClick = { evidenceExpanded = !evidenceExpanded }) {
                    Text(if (evidenceExpanded) "Evidence ausblenden" else "Evidence anzeigen")
                }
                if (evidenceExpanded) {
                    evidenceIds.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, enabled = actionEnabled) {
                    Text(if (actionRunning) "Prüfung läuft …" else actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: ToolCenterProviderUiModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(provider.capabilityId, style = MaterialTheme.typography.titleSmall)
                Text(provider.stateLabel, style = MaterialTheme.typography.labelLarge, color = toneColor(provider.tone))
            }
            Text("${provider.providerId} · ${provider.providerTypeLabel}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Trust ${provider.trustLabel} · Reliability ${provider.reliabilityPercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DetailLine("Inputs", provider.requiredInputs)
            DetailLine("Outputs", provider.outputs)
        }
    }
}

@Composable
private fun DetailLine(label: String, values: List<String>) {
    Text(
        "$label: ${values.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptySection(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun toneColor(tone: ToolCenterTone): Color = when (tone) {
    ToolCenterTone.POSITIVE -> MaterialTheme.colorScheme.primary
    ToolCenterTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ToolCenterTone.WARNING -> MaterialTheme.colorScheme.tertiary
    ToolCenterTone.NEGATIVE -> MaterialTheme.colorScheme.error
    ToolCenterTone.MUTED -> MaterialTheme.colorScheme.outline
}
