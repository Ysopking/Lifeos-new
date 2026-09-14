package app.lifeos.next.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.next.LifeOsProcessStartupPhase
import app.lifeos.next.LifeOsProcessStartupState
import app.lifeos.next.ui.theme.LifeOsTheme

@Composable
fun LifeOsStartupScreen(
    state: LifeOsProcessStartupState,
    modifier: Modifier = Modifier,
) {
    LifeOsTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("LIFEOS", style = MaterialTheme.typography.headlineLarge)
            when (state.phase) {
                LifeOsProcessStartupPhase.STARTING -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
                    Text(state.stage, style = MaterialTheme.typography.bodyLarge)
                }
                LifeOsProcessStartupPhase.FAILED -> {
                    Text(
                        state.failure ?: "BootEngine konnte nicht gestartet werden.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
                LifeOsProcessStartupPhase.READY -> {
                    Text("Runtime bereit", modifier = Modifier.padding(top = 20.dp))
                }
            }
        }
    }
}
