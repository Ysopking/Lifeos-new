package app.lifeos.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lifeos.next.LifeOsProcessStartupPhase
import app.lifeos.next.LifeOsProcessStartupState
import app.lifeos.next.ui.theme.LifeOsTheme
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsStartupScreen(
    state: LifeOsProcessStartupState,
    modifier: Modifier = Modifier,
) {
    LifeOsTheme {
        val background = Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
                MaterialTheme.colorScheme.background,
            )
        )
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(background)
                .padding(LifeOsTokens.Spacing.xLarge),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = LifeOsTokens.Elevation.floating,
            ) {
                Column(
                    modifier = Modifier.padding(LifeOsTokens.Spacing.xxLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("L", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LIFEOS", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Private Cognitive Runtime",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(LifeOsTokens.Spacing.xSmall))
                    when (state.phase) {
                        LifeOsProcessStartupPhase.STARTING -> {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Text(state.stage, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Lokale Zustände werden geprüft, rehydriert und sicher verbunden.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LifeOsProcessStartupPhase.FAILED -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(
                                    state.failure ?: "BootEngine konnte nicht gestartet werden.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(LifeOsTokens.Spacing.large),
                                )
                            }
                        }
                        LifeOsProcessStartupPhase.READY -> {
                            Text(
                                "Runtime bereit",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
