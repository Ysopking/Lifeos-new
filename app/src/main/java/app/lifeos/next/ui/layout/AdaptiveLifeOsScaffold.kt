package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun AdaptiveLifeOsScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        when (LifeOsWindowClass.fromWidthDp(maxWidth.value)) {
            LifeOsWindowClass.COMPACT -> CompactScaffold(selected, onSelect, content)
            LifeOsWindowClass.MEDIUM -> MediumScaffold(selected, onSelect, content)
            LifeOsWindowClass.EXPANDED -> ExpandedScaffold(selected, onSelect, content)
        }
    }
}

@Composable
private fun CompactScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                LifeOsDestination.ordered.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.semantics {
                            contentDescription = LifeOsSemantics.navigationLabel(destination.label)
                        },
                        selected = destination == selected,
                        onClick = { onSelect(destination) },
                        icon = { DestinationGlyph(destination) },
                        label = { Text(destination.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MediumScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surface,
                header = {
                    BrandMark(compact = true)
                    Spacer(Modifier.height(LifeOsTokens.Spacing.large))
                },
            ) {
                LifeOsDestination.ordered.forEach { destination ->
                    NavigationRailItem(
                        modifier = Modifier.semantics {
                            contentDescription = LifeOsSemantics.navigationLabel(destination.label)
                        },
                        selected = destination == selected,
                        onClick = { onSelect(destination) },
                        icon = { DestinationGlyph(destination) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ExpandedScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(LifeOsTokens.Layout.expandedNavigationWidth)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(
                        horizontal = LifeOsTokens.Spacing.medium,
                        vertical = LifeOsTokens.Spacing.large,
                    ),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                BrandMark(compact = false)
                Spacer(Modifier.height(LifeOsTokens.Spacing.large))
                LifeOsDestination.ordered.forEach { destination ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        selected = destination == selected,
                        onClick = { onSelect(destination) },
                        icon = { DestinationGlyph(destination) },
                        modifier = Modifier.semantics {
                            contentDescription = LifeOsSemantics.navigationLabel(destination.label)
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "LOCAL · PRIVATE · AUDITABLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = LifeOsTokens.Spacing.medium,
                        vertical = LifeOsTokens.Spacing.small,
                    ),
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DestinationGlyph(destination: LifeOsDestination) {
    Text(
        text = destination.glyph,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clearAndSetSemantics { },
    )
}

@Composable
private fun BrandMark(compact: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = if (compact) 0.dp else LifeOsTokens.Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "L",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (!compact) {
            Column {
                Text(
                    text = "LIFEOS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Cognitive Runtime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
