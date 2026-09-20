package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.PersonalConversationImportViewModel
import app.lifeos.next.R
import app.lifeos.next.StorageMaintenanceViewModel
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsSystemOverlay(
    model: LifeOsChatViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    storageMaintenanceModel: StorageMaintenanceViewModel,
    personalConversationImportModel: PersonalConversationImportViewModel,
    ownerAttention: OwnerAttentionUiState,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .semantics { paneTitle = "System" },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = LifeOsTokens.Layout.compactHorizontalPadding,
                            vertical = LifeOsTokens.Spacing.small,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "System",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = "System schließen"
                        },
                        onClick = onDismiss,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = null,
                        )
                    }
                }

                LifeOsSystemHub(
                    model = model,
                    decisionTraceModel = decisionTraceModel,
                    toolCenterModel = toolCenterModel,
                    assetReviewModel = assetReviewModel,
                    storageMaintenanceModel = storageMaintenanceModel,
                    personalConversationImportModel = personalConversationImportModel,
                    ownerAttention = ownerAttention,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
