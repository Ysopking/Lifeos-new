package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.LifeOsDestination

@Composable
fun AdaptiveLifeOsScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        when (LifeOsWindowClass.fromWidthDp(maxWidth.value)) {
            LifeOsWindowClass.COMPACT -> Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        LifeOsDestination.ordered.forEach { destination ->
                            NavigationBarItem(
                                selected = destination == selected,
                                onClick = { onSelect(destination) },
                                icon = { Text(destination.glyph) },
                                label = { Text(destination.label) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                },
            ) { innerPadding ->
                content(Modifier.fillMaxSize().padding(innerPadding))
            }

            LifeOsWindowClass.MEDIUM,
            LifeOsWindowClass.EXPANDED -> Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    LifeOsDestination.ordered.forEach { destination ->
                        NavigationRailItem(
                            selected = destination == selected,
                            onClick = { onSelect(destination) },
                            icon = { Text(destination.glyph) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    content(Modifier.fillMaxSize())
                }
            }
        }
    }
}
