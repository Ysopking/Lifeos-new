package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lifeos.next.LifeOsApplication
import app.lifeos.next.kernel.WebDeepSearchOwnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owner-facing switch over the durable OwnerPolicy ledger; UI state is never an authority. */
@Composable
internal fun WebDeepSearchOwnerControl(
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as LifeOsApplication
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        enabled = withContext(Dispatchers.IO) {
            WebDeepSearchOwnerPolicy.enabled(application.ownerPolicy)
        }
        loaded = true
    }

    LaunchedEffect(application) {
        runCatching { refresh() }
            .onFailure { error = "Owner-Policy konnte nicht gelesen werden." }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(4f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Web DeepSearch", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) {
                        "Web-Evidenz ist per Owner Policy freigegeben. Neue Treffer werden als Photonen gespeichert."
                    } else {
                        "Aus. Lokale DeepSearch bleibt verfügbar; Netzwerkzugriff ist blockiert."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                enabled = loaded && !busy,
                onCheckedChange = { requested ->
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                if (requested) {
                                    WebDeepSearchOwnerPolicy.enable(application.ownerPolicy)
                                } else {
                                    WebDeepSearchOwnerPolicy.disable(application.ownerPolicy)
                                }
                            }
                            refresh()
                        }.onFailure {
                            error = "Owner-Policy-Änderung fehlgeschlagen."
                        }
                        busy = false
                    }
                },
            )
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
