package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val attention: Boolean = false,
)

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
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(
                    modifier = Modifier
                        .size(LifeOsTokens.Size.minimumTouchTarget)
                        .semantics {
                            contentDescription = "System öffnen"
                        },
                    onClick = onOpenSystem,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_system),
                        contentDescription = null,
                        modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                    )
                }
                if (state.attention) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
