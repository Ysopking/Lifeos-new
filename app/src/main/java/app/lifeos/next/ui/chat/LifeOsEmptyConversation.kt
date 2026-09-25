package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.theme.LifeOsTokens

private val Suggestions = listOf(
    "Was ist heute wichtig?",
    "Finde etwas in meinem Gedächtnis",
    "Hilf mir, etwas zu erledigen",
)

@Composable
fun LifeOsEmptyConversation(
    contextReady: Boolean,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = LifeOsTokens.Spacing.xxLarge),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
    ) {
        Text(
            text = if (contextReady) {
                "Was möchtest du erledigen?"
            } else {
                "Einen Moment."
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (contextReady) {
                "Schreib einfach, was du brauchst."
            } else {
                "LIFEOS richtet deinen persönlichen Kontext ein."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (contextReady) {
            Column(
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                Suggestions.forEach { suggestion ->
                    TextButton(
                        onClick = { onSuggestion(suggestion) },
                    ) {
                        Text(suggestion)
                    }
                }
            }
        }
    }
}
