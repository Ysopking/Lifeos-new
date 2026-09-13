package app.lifeos.next.ui.assets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecision
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRecord
import app.lifeos.next.AssetReviewFilter
import app.lifeos.next.OwnerAssetReviewUiState
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.ui.components.PhotonImagePreviewState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun OwnerAssetReviewScreen(
    model: OwnerAssetReviewViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Assets", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Generierte Assets werden erst nach deiner Bestätigung veröffentlicht.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TabRow(selectedTabIndex = state.filter.ordinal) {
            AssetReviewFilter.entries.forEach { filter ->
                Tab(
                    selected = state.filter == filter,
                    onClick = { model.selectFilter(filter) },
                    text = {
                        Text(
                            when (filter) {
                                AssetReviewFilter.PENDING -> "Ausstehend ${state.pendingCount}"
                                AssetReviewFilter.APPROVED -> "Bestätigt ${state.approvedCount}"
                                AssetReviewFilter.FEEDBACK -> "Feedback ${state.feedbackCount}"
                            }
                        )
                    },
                )
            }
        }

        if (state.visibleRecords.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    when (state.filter) {
                        AssetReviewFilter.PENDING -> "Keine Assets warten auf deine Freigabe."
                        AssetReviewFilter.APPROVED -> "Noch keine bestätigten Assets."
                        AssetReviewFilter.FEEDBACK -> "Noch keine Änderungswünsche oder Ablehnungen."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = state.visibleRecords,
                    key = { it.candidate.id.value },
                ) { record ->
                    AssetReviewCard(
                        record = record,
                        state = state,
                        onFeedbackChange = { model.editFeedback(record.candidate.id, it) },
                        onApprove = { model.approve(record.candidate.id) },
                        onRequestChanges = { model.requestChanges(record.candidate.id) },
                        onReject = { model.reject(record.candidate.id) },
                    )
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
    }
}

@Composable
private fun AssetReviewCard(
    record: OwnerAssetReviewRecord,
    state: OwnerAssetReviewUiState,
    onFeedbackChange: (String) -> Unit,
    onApprove: () -> Unit,
    onRequestChanges: () -> Unit,
    onReject: () -> Unit,
) {
    val candidate = record.candidate
    val id = candidate.id.value
    val busy = id in state.busyCandidateIds
    val feedback = state.feedbackDrafts[id].orEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${candidate.kind.name} · ${candidate.targetMimeType}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(reviewStatus(record), style = MaterialTheme.typography.labelMedium)
            }

            ImagePreview(state.previewStates[id])

            candidate.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(preview, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "Revision ${candidate.revisionKey.take(16)}… · ${ASSET_REVIEW_TIME.format(candidate.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Module: ${candidate.participatingModules.sorted().joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
            )
            candidate.materializedAsset?.let { asset ->
                Text(
                    "Asset: ${asset.byteCount} Byte · SHA-256 ${asset.sha256.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (candidate.inputPhotonIds.isNotEmpty()) {
                Text(
                    "Eingaben: ${candidate.inputPhotonIds.size} Photon${if (candidate.inputPhotonIds.size == 1) "" else "en"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            candidate.metadata.toSortedMap().forEach { (key, value) ->
                Text("$key: $value", style = MaterialTheme.typography.bodySmall)
            }

            record.decision?.let { decision ->
                decision.feedback?.takeIf { it.isNotBlank() }?.let { value ->
                    Text("Dein Feedback: $value", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Entschieden ${ASSET_REVIEW_TIME.format(decision.decidedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (record.decision == null) {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = onFeedbackChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feedback / gewünschte Änderungen") },
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onApprove,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Bestätigen")
                    }
                    OutlinedButton(
                        onClick = onRequestChanges,
                        enabled = !busy && feedback.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Ändern")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Ablehnen")
                    }
                }
                if (busy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(state: PhotonImagePreviewState?) {
    when (state) {
        PhotonImagePreviewState.Loading -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        is PhotonImagePreviewState.Ready -> Image(
            bitmap = state.preview.bitmap.asImageBitmap(),
            contentDescription = "Vorschau des generierten Assets",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentScale = ContentScale.Fit,
        )
        is PhotonImagePreviewState.Failed -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        null -> Unit
    }
}

private fun reviewStatus(record: OwnerAssetReviewRecord): String = when (record.decision?.decision) {
    null -> "Ausstehend"
    OwnerAssetReviewDecision.APPROVED -> if (record.publishedAt != null) "Bestätigt" else "Wird veröffentlicht"
    OwnerAssetReviewDecision.CHANGES_REQUESTED -> "Änderungen gewünscht"
    OwnerAssetReviewDecision.REJECTED -> "Abgelehnt"
}

private val ASSET_REVIEW_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
