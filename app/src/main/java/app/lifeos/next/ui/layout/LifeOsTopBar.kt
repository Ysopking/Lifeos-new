package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.semantics.semantics
import app.lifeos.next.R
import app.lifeos.next.ui.theme.LifeOsTokens

data class LifeOsTopBarState(
    val title: String,
    val attentionCount: Int = 0,
) {
    init {
        require(attentionCount >= 0)
    }
}

@Composable
fun LifeOsTopBar(
    state: LifeOsTopBarState,
    onOpenSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = LifeOsTokens.Elevation.resting,
    ) {
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
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
            )
            BadgedBox(
                badge = {
                    if (state.attentionCount > 0) {
                        Badge {
                            Text(attentionBadgeLabel(state.attentionCount))
                        }
                    }
                },
            ) {
                IconButton(
                    modifier = Modifier
                        .size(LifeOsTokens.Size.minimumTouchTarget)
                        .semantics {
                            contentDescription = systemActionDescription(state.attentionCount)
                        },
                    onClick = onOpenSystem,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_system),
                        contentDescription = null,
                        modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                    )
                }
            }
        }
    }
}

internal fun attentionBadgeLabel(attentionCount: Int): String =
    if (attentionCount > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else attentionCount.toString()

internal fun systemActionDescription(attentionCount: Int): String =
    if (attentionCount > 0) {
        "System öffnen, $attentionCount Punkte brauchen dich"
    } else {
        "System öffnen"
    }

private const val MAX_BADGE_COUNT = 9
