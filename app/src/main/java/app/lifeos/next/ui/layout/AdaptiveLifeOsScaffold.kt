package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.LifeOsDestination

/**
 * Minimal shell around a chat-first product.
 *
 * Today and Memory remain available from the title switcher, but permanent bottom/rail navigation
 * no longer competes with the conversation. System/owner-attention remains the only persistent
 * secondary action.
 */
@Composable
fun AdaptiveLifeOsScaffold(
    selected: LifeOsDestination,
    onSelect: (LifeOsDestination) -> Unit,
    onOpenSystem: () -> Unit,
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
                ),
                selected = selected,
                onSelect = onSelect,
                onOpenSystem = onOpenSystem,
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
