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
    "Finde alles zu …",
    "Erstelle daraus …",
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
            text = "LIFEOS",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (contextReady) {
                "Was möchtest du tun?"
            } else {
                "Dein persönlicher Gedächtniskontext wird vorbereitet."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (contextReady) {
                "Frag mich etwas, plane einen nächsten Schritt oder arbeite mit deinem Gedächtnis."
            } else {
                "Sobald der Kontext bereit ist, kannst du mit LIFEOS arbeiten."
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
