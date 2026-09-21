package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun ChatMessageContent(
    event: ChatEvent,
    modifier: Modifier = Modifier,
) {
    when (event.role) {
        ChatRole.USER -> UserMessage(event, modifier)
        ChatRole.LIFEOS -> LifeOsMessage(event, modifier)
        ChatRole.SYSTEM -> SystemMessage(event, modifier)
    }
}

@Composable
private fun UserMessage(
    event: ChatEvent,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.84f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            event.text?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(
                        horizontal = LifeOsTokens.Spacing.large,
                        vertical = LifeOsTokens.Spacing.medium,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LifeOsMessage(
    event: ChatEvent,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = LifeOsTokens.Layout.readingMaxWidth),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
    ) {
        event.text?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SystemMessage(
    event: ChatEvent,
    modifier: Modifier,
) {
    event.text?.let { text ->
        Text(
            text = text,
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
