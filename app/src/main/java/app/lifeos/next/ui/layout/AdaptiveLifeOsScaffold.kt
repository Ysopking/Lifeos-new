package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun AdaptiveLifeOsScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    onOpenSystem: () -> Unit,
    attention: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        when (LifeOsWindowClass.fromWidthDp(maxWidth.value)) {
            LifeOsWindowClass.COMPACT -> Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    LifeOsTopBar(
                        state = LifeOsTopBarState(
                            title = selected.label,
                            attention = attention,
                        ),
                        onOpenSystem = onOpenSystem,
                    )
                },
                bottomBar = {
                    NavigationBar {
                        LifeOsDestination.ordered.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        LifeOsSemantics.navigationLabel(destination.label)
                                },
                                selected = destination == selected,
                                onClick = { onSelect(destination) },
                                icon = {
                                    Icon(
                                        painter = painterResource(destination.iconRes),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(destination.label) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                },
            ) { innerPadding ->
                content(Modifier.fillMaxSize().padding(innerPadding))
            }

            LifeOsWindowClass.MEDIUM -> Row(modifier = Modifier.fillMaxSize()) {
                PrimaryNavigationRail(
                    selected = selected,
                    onSelect = onSelect,
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    LifeOsTopBar(
                        state = LifeOsTopBarState(
                            title = selected.label,
                            attention = attention,
                        ),
                        onOpenSystem = onOpenSystem,
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(Modifier.fillMaxSize())
                    }
                }
            }

            LifeOsWindowClass.EXPANDED -> Row(modifier = Modifier.fillMaxSize()) {
                PrimaryNavigationRail(
                    selected = selected,
                    onSelect = onSelect,
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    LifeOsTopBar(
                        state = LifeOsTopBarState(
                            title = selected.label,
                            attention = attention,
                        ),
                        onOpenSystem = onOpenSystem,
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryNavigationRail(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .width(LifeOsTokens.Layout.navigationRailWidth)
    ) {
        LifeOsDestination.ordered.forEach { destination ->
            NavigationRailItem(
                modifier = Modifier.semantics {
                    contentDescription =
                        LifeOsSemantics.navigationLabel(destination.label)
                },
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}
