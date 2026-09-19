package app.lifeos.next.ui.assets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRecord
import app.lifeos.next.AssetReviewFilter
import app.lifeos.next.OwnerAssetReviewUiState
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.R
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun OwnerAssetReviewScreen(
    model: OwnerAssetReviewViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    var selectedCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRecord = selectedCandidateId?.let { id ->
        state.records.firstOrNull { it.candidate.id.value == id }
    }

    BackHandler(enabled = selectedRecord != null) {
        selectedCandidateId = null
    }

    if (selectedRecord == null) {
        AssetReviewOverview(
            state = state,
            onSelectFilter = { model.selectFilter(it) },
            onOpenRecord = { selectedCandidateId = it.candidate.id.value },
            onDismissError = model::dismissError,
            modifier = modifier,
        )
    } else {
        AssetReviewDetail(
            record = selectedRecord,
            state = state,
            onBack = { selectedCandidateId = null },
            onFeedbackChange = { model.editFeedback(selectedRecord.candidate.id, it) },
            onApprove = { model.approve(selectedRecord.candidate.id) },
            onRequestChanges = { model.requestChanges(selectedRecord.candidate.id) },
            onReject = { model.reject(selectedRecord.candidate.id) },
            onDismissError = model::dismissError,
            modifier = modifier,
        )
    }
}

@Composable
private fun AssetReviewOverview(
    state: OwnerAssetReviewUiState,
    onSelectFilter: (AssetReviewFilter) -> Unit,
    onOpenRecord: (OwnerAssetReviewRecord) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = LifeOsTokens.Layout.compactHorizontalPadding,
            vertical = LifeOsTokens.Spacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall)) {
            Text("Asset-Freigaben", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Prüfe neue Ergebnisse, bevor LIFEOS sie veröffentlicht oder weiterverwendet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TabRow(selectedTabIndex = state.filter.ordinal) {
            AssetReviewFilter.entries.forEach { filter ->
                Tab(
                    selected = state.filter == filter,
                    onClick = { onSelectFilter(filter) },
                    text = { Text(filterLabel(filter, state)) },
                )
            }
        }

        state.error?.let { AssetReviewErrorNotice(it, onDismissError) }

        if (state.visibleRecords.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(emptyTitle(state.filter), style = MaterialTheme.typography.titleMedium)
                Text(
                    emptyBody(state.filter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                items(
                    items = state.visibleRecords,
                    key = { it.candidate.id.value },
                ) { record ->
                    AssetReviewSummaryCard(
                        record = record,
                        previewState = state.previewStates[record.candidate.id.value],
                        onOpen = { onOpenRecord(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetReviewDetail(
    record: OwnerAssetReviewRecord,
    state: OwnerAssetReviewUiState,
    onBack: () -> Unit,
    onFeedbackChange: (String) -> Unit,
    onApprove: () -> Unit,
    onRequestChanges: () -> Unit,
    onReject: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier,
) {
    val candidate = record.candidate
    val feedback = state.feedbackDrafts[candidate.id.value].orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(
            horizontal = LifeOsTokens.Layout.compactHorizontalPadding,
            vertical = LifeOsTokens.Spacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.large),
    ) {
        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "Zurück zur Freigaben-Übersicht"
                },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = null,
                        modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                    )
                    Text("Zur Übersicht")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small)) {
                ReviewStatusBadge(record)
                Text(candidate.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${assetKindLabel(candidate.kind)} · ${ASSET_REVIEW_TIME.format(candidate.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { error ->
            item { AssetReviewErrorNotice(error, onDismissError) }
        }
        item {
            AssetReviewPreview(
                record = record,
                state = state.previewStates[candidate.id.value],
            )
        }
        item {
            AssetReviewDecisionPanel(
                record = record,
                state = state,
                feedback = feedback,
                onFeedbackChange = onFeedbackChange,
                onApprove = onApprove,
                onRequestChanges = onRequestChanges,
                onReject = onReject,
            )
        }
        item { TechnicalEvidence(record) }
    }
}
