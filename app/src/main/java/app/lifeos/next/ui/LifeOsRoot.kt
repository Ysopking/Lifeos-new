package app.lifeos.next.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.ui.chat.LifeOsChatScreen
import app.lifeos.next.ui.system.SystemRuntimeHealthScreen

@Composable
fun LifeOsRoot(
    model: LifeOsChatViewModel,
    onRequestMicrophonePermission: () -> Unit = {},
) {
    var selectedKey by rememberSaveable {
        mutableStateOf(LifeOsDestination.default.key)
    }
    val selected = LifeOsDestination.fromKey(selectedKey)

    BackHandler(enabled = selected != LifeOsDestination.CHAT) {
        selectedKey = LifeOsDestination.CHAT.key
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                LifeOsNavigationBar(
                    selected = selected,
                    onSelect = { destination -> selectedKey = destination.key },
                )
            },
        ) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (selected) {
                LifeOsDestination.CHAT -> LifeOsChatScreen(
                    model = model,
                    modifier = contentModifier,
                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                )
                LifeOsDestination.MEMORY -> MemoryMigrationScreen(contentModifier)
                LifeOsDestination.GOALS -> GoalsMigrationScreen(contentModifier)
                LifeOsDestination.SYSTEM -> SystemRuntimeHealthScreen(model, contentModifier)
            }
        }
    }
}

@Composable
private fun LifeOsNavigationBar(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
) {
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
}
