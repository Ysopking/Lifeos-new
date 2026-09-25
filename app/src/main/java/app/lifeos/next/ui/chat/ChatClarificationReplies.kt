package app.lifeos.next.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lifeos.next.ui.theme.LifeOsTokens

/**
 * Chat-first owner clarification UI.
 *
 * LIFEOS may ask one concise question in the normal conversation. When the language layer exposes
 * bounded alternatives, they are rendered as one-tap replies directly above the composer. No
 * separate wizard, modal or second interaction model is introduced.
 */
internal object ChatClarificationPolicy {
    fun options(
        required: Boolean,
        alternatives: Collection<String>,
    ): List<String> {
        if (!required) return emptyList()
        return alternatives
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_OPTIONS)
            .toList()
    }

    private const val MAX_OPTIONS = 4
}

@Composable
internal fun ChatClarificationReplies(
    options: List<String>,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
    ) {
        options.forEach { option ->
            TextButton(
                onClick = { onReply(option) },
                modifier = Modifier.semantics {
                    contentDescription = "Antworten: $option"
                },
            ) {
                Text(option)
            }
        }
    }
}
