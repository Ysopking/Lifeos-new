package app.lifeos.next.ui.assets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecision
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRecord
import app.lifeos.next.AssetReviewFilter
import app.lifeos.next.OwnerAssetReviewUiState
import app.lifeos.next.ui.components.PhotonImagePreviewState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AssetReviewSummaryCard(
    record: OwnerAssetReviewRecord,
    previewState: PhotonImagePreviewState?,
    onOpen: () -> Unit,
) {
    val candidate = record.candidate
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${assetKindLabel(candidate.kind)} · ${ASSET_REVIEW_TIME.format(candidate.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReviewStatusBadge(record)
            }
            if (candidate.kind == ArtifactKind.IMAGE) {
                AssetImagePreview(previewState, 180)
            } else {
                candidate.previewText?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(it.take(180), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            val context = listOfNotNull(
                candidate.participatingModules.takeIf { it.isNotEmpty() }?.let { "${it.size} Module" },
                candidate.inputPhotonIds.takeIf { it.isNotEmpty() }?.let { "${it.size} Quellen" },
            ).joinToString(" · ")
            if (context.isNotBlank()) {
                Text(
                    context,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(if (record.decision == null) "Prüfen" else "Details ansehen")
            }
        }
    }
}

@Composable
internal fun AssetReviewDecisionPanel(
    record: OwnerAssetReviewRecord,
    state: OwnerAssetReviewUiState,
    feedback: String,
    onFeedbackChange: (String) -> Unit,
    onApprove: () -> Unit,
    onRequestChanges: () -> Unit,
    onReject: () -> Unit,
) {
    val busy = record.candidate.id.value in state.busyCandidateIds
    if (record.decision != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Entscheidung", style = MaterialTheme.typography.titleSmall)
                Text(reviewStatus(record))
                record.decision.feedback?.takeIf { it.isNotBlank() }?.let { Text("Rückmeldung: $it") }
                Text(
                    "Entschieden ${ASSET_REVIEW_TIME.format(record.decision.decidedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Deine Entscheidung", style = MaterialTheme.typography.titleMedium)
        Text(
            "Freigeben veröffentlicht exakt diese Revision. Für Änderungen ist eine Rückmeldung erforderlich.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = feedback,
            onValueChange = onFeedbackChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Rückmeldung oder gewünschte Änderungen") },
            enabled = !busy,
            minLines = 3,
            maxLines = 6,
        )
        Button(onClick = onApprove, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Diese Revision freigeben")
        }
        OutlinedButton(
            onClick = onRequestChanges,
            enabled = !busy && feedback.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Änderungen anfordern")
        }
        TextButton(
            onClick = onReject,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Asset ablehnen")
        }
        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
internal fun AssetReviewPreview(record: OwnerAssetReviewRecord, state: PhotonImagePreviewState?) {
    val candidate = record.candidate
    if (candidate.kind == ArtifactKind.IMAGE) {
        AssetImagePreview(state, 420)
    } else {
        candidate.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (candidate.kind == ArtifactKind.CODE) "Exakte Code-Revision" else "Vorschau",
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    SelectionContainer {
                        Text(
                            preview,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (candidate.kind == ArtifactKind.CODE) FontFamily.Monospace else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TechnicalEvidence(record: OwnerAssetReviewRecord) {
    val candidate = record.candidate
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("Technische Evidenz", style = MaterialTheme.typography.titleMedium)
        EvidenceRow("Revision", candidate.revisionKey)
        EvidenceRow("MIME", candidate.targetMimeType)
        EvidenceRow("Module", candidate.participatingModules.sorted().joinToString(", ").ifBlank { "Keine" })
        EvidenceRow("Quellen", candidate.inputPhotonIds.size.toString())
        candidate.materializedAsset?.let {
            EvidenceRow("Größe", "${it.byteCount} Byte")
            EvidenceRow("SHA-256", it.sha256)
        }
        candidate.metadata.toSortedMap().forEach { (key, value) -> EvidenceRow(key, value) }
    }
}

@Composable
private fun EvidenceRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ReviewStatusBadge(record: OwnerAssetReviewRecord) {
    val background = when (record.decision?.decision) {
        null -> MaterialTheme.colorScheme.tertiaryContainer
        OwnerAssetReviewDecision.APPROVED -> MaterialTheme.colorScheme.primaryContainer
        OwnerAssetReviewDecision.CHANGES_REQUESTED -> MaterialTheme.colorScheme.secondaryContainer
        OwnerAssetReviewDecision.REJECTED -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(shape = RoundedCornerShape(999.dp), color = background) {
        Text(reviewStatus(record), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun AssetReviewErrorNotice(error: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Hinweis schließen") }
        }
    }
}

@Composable
private fun AssetImagePreview(state: PhotonImagePreviewState?, maxHeight: Int) {
    when (state) {
        PhotonImagePreviewState.Loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        is PhotonImagePreviewState.Ready -> Image(
            bitmap = state.preview.bitmap.asImageBitmap(),
            contentDescription = "Vorschau des generierten Assets",
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight.dp),
            contentScale = ContentScale.Fit,
        )
        is PhotonImagePreviewState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
}

internal fun filterLabel(filter: AssetReviewFilter, state: OwnerAssetReviewUiState): String = when (filter) {
    AssetReviewFilter.PENDING -> "Offen ${state.pendingCount}"
    AssetReviewFilter.APPROVED -> "Freigegeben ${state.approvedCount}"
    AssetReviewFilter.FEEDBACK -> "Rückmeldung ${state.feedbackCount}"
}

internal fun emptyTitle(filter: AssetReviewFilter): String = when (filter) {
    AssetReviewFilter.PENDING -> "Alles geprüft"
    AssetReviewFilter.APPROVED -> "Noch nichts freigegeben"
    AssetReviewFilter.FEEDBACK -> "Noch keine Rückmeldungen"
}

internal fun emptyBody(filter: AssetReviewFilter): String = when (filter) {
    AssetReviewFilter.PENDING -> "Aktuell wartet kein neues Asset auf deine Entscheidung."
    AssetReviewFilter.APPROVED -> "Freigegebene Assets erscheinen hier mit ihrer Entscheidungshistorie."
    AssetReviewFilter.FEEDBACK -> "Änderungswünsche und abgelehnte Assets werden hier gesammelt."
}

internal fun assetKindLabel(kind: ArtifactKind): String = when (kind) {
    ArtifactKind.DOCUMENT -> "Dokument"
    ArtifactKind.IMAGE -> "Bild"
    ArtifactKind.CODE -> "Code"
    ArtifactKind.REPORT -> "Bericht"
    ArtifactKind.OTHER -> "Asset"
}

internal fun reviewStatus(record: OwnerAssetReviewRecord): String = when (record.decision?.decision) {
    null -> "Prüfung offen"
    OwnerAssetReviewDecision.APPROVED -> if (record.publishedAt != null) "Freigegeben" else "Wird veröffentlicht"
    OwnerAssetReviewDecision.CHANGES_REQUESTED -> "Änderungen angefordert"
    OwnerAssetReviewDecision.REJECTED -> "Abgelehnt"
}

internal val ASSET_REVIEW_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
