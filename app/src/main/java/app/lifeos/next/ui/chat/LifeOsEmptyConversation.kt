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
    "Was ist diese Woche wichtig?",
    "Zeig mir offene Rückfragen.",
    "Woran soll ich bei meinen Projekten weiterarbeiten?",
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
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.large),
    ) {
        Text(
            text = if (contextReady) {
                "Was soll LIFEOS für dich tun?"
            } else {
                "Dein Kontext wird aufgebaut …"
            },
            style = MaterialTheme.typography.headlineMedium,
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
