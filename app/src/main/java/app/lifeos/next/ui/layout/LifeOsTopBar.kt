package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lifeos.next.R
import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.theme.LifeOsTokens

data class LifeOsTopBarState(
    val title: String,
    val attentionCount: Int = 0,
) {
    init {
        require(attentionCount >= 0)
    }
}

/**
 * Chat-first top bar.
 *
 * Primary navigation is deliberately progressive: the current surface stays visible while
 * secondary workspaces are one tap away instead of permanently consuming screen space.
 */
@Composable
fun LifeOsTopBar(
    state: LifeOsTopBarState,
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    onOpenSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var switcherOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = LifeOsTokens.Elevation.resting,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LifeOsTokens.Spacing.small,
                    end = LifeOsTokens.Spacing.small,
                    top = LifeOsTokens.Spacing.xSmall,
                    bottom = LifeOsTokens.Spacing.xSmall,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(
                    onClick = { switcherOpen = true },
                    modifier = Modifier.semantics {
                        contentDescription = workspaceSwitcherDescription(selected)
                    },
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "  ▾",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = switcherOpen,
                    onDismissRequest = { switcherOpen = false },
                ) {
                    LifeOsDestination.ordered.forEach { destination ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = destinationMenuLabel(destination),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            onClick = {
                                switcherOpen = false
                                onSelect(destination)
                            },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Zu ${destinationMenuLabel(destination)} wechseln"
                            },
                            trailingIcon = if (destination == selected) {
                                {
                                    Text(
                                        text = "Aktiv",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            if (state.attentionCount > 0) {
                TextButton(
                    onClick = onOpenSystem,
                    modifier = Modifier.semantics {
                        contentDescription = systemActionDescription(state.attentionCount)
                    },
                ) {
                    Text(
                        text = ownerAttentionActionLabel(state.attentionCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun destinationMenuLabel(destination: LifeOsDestination): String = when (destination) {
    LifeOsDestination.CHAT -> "Chat"
    LifeOsDestination.GOALS -> destination.label
    LifeOsDestination.MEMORY -> destination.label
}

internal fun workspaceSwitcherDescription(selected: LifeOsDestination): String =
    "Bereich wechseln, aktuell ${destinationMenuLabel(selected)}"

internal fun ownerAttentionActionLabel(attentionCount: Int): String {
    require(attentionCount > 0)
    return if (attentionCount > MAX_BADGE_COUNT) {
        "${MAX_BADGE_COUNT}+ offen"
    } else {
        "$attentionCount offen"
    }
}

internal fun systemActionDescription(attentionCount: Int): String =
    if (attentionCount > 0) {
        "System öffnen, $attentionCount Punkte brauchen dich"
    } else {
        "System öffnen"
    }

private const val MAX_BADGE_COUNT = 9
