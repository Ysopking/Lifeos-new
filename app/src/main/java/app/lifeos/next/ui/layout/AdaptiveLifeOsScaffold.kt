package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.LifeOsDestination

/**
 * Chat-first shell. Secondary workspaces never occupy permanent navigation chrome.
 */
@Composable
fun AdaptiveLifeOsScaffold(
    selected: LifeOsDestination,
    onOpenHub: () -> Unit,
    onBackToChat: () -> Unit,
    attentionCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LifeOsTopBar(
                state = LifeOsTopBarState(
                    title = selected.label,
                    attentionCount = attentionCount,
                    showBack = selected != LifeOsDestination.CHAT,
                ),
                onBack = onBackToChat,
                onOpenHub = onOpenHub,
            )
        },
    ) { innerPadding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
