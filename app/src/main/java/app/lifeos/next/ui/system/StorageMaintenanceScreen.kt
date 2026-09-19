package app.lifeos.next.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.StorageMaintenanceViewModel
import java.text.DecimalFormat

@Composable
fun StorageMaintenanceScreen(
    model: StorageMaintenanceViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Speicherpflege", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "LIFEOS liest den erreichbaren Dateibestand hardware-adaptiv, erkennt exakte Duplikate und schlägt Neuordnung vor. Bereinigung geht zuerst in den reversiblen LIFEOS-Papierkorb.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!state.broadFileAccess) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Vollständiger Shared-Storage-Zugriff ist noch nicht erteilt. Ohne Android-Freigabe kann LIFEOS geschützte oder nicht erreichbare Bereiche nicht lesen.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Analyse", style = MaterialTheme.typography.titleMedium)
                    Text("Phase: " + state.scanPhase)
                    Text(
                        "Indexiert: " + state.indexedFiles + " Dateien · " + formatBytes(state.indexedBytes)
                    )
                    Text(
                        "Vollständig gelesen: " + state.fingerprintedFiles + " Dateien · " +
                            formatBytes(state.fingerprintedBytes)
                    )
                    if (!state.contentReadComplete) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    OutlinedButton(
                        onClick = model::continueScan,
                        enabled = state.broadFileAccess && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.contentReadComplete) "Neu prüfen" else "Scan fortsetzen")
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Vorschläge", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.cleanupCandidateCount.toString() +
                            " Bereinigungen · " +
                            state.reorganizationCandidateCount +
                            " Neuordnungen"
                    )
                    Text("Erkennbar bereinigbar: " + formatBytes(state.reclaimableBytes))
                    Text(
                        "Das Verschieben in den LIFEOS-Papierkorb ist reversibel und gibt noch keinen Speicherplatz frei. Frei wird der Platz erst beim endgültigen Löschen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!state.authorized) {
                        Button(
                            onClick = model::authorize,
                            enabled = state.broadFileAccess && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Speicherpflege erlauben")
                        }
                        Text(
                            "Diese Freigabe wird als Owner-Policy gespeichert. Ohne sie werden Dateiänderungen fail-closed blockiert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = model::applySafeCleanup,
                            enabled = !state.busy && state.cleanupCandidateCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sicher bereinigen → Papierkorb")
                        }
                        OutlinedButton(
                            onClick = model::applyOrganization,
                            enabled = !state.busy && state.indexedFiles > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Dateien neu einordnen · nächster Batch")
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LIFEOS-Papierkorb", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.trashItems.size.toString() + " Dateien · " + formatBytes(state.trashBytes)
                    )
                    Text(
                        "Endgültiges Löschen betrifft nur Einträge, die mindestens 30 Tage im LIFEOS-Papierkorb liegen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = model::purgeExpiredTrash,
                        enabled = state.authorized && !state.busy && state.trashItems.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Papierkorb >30 Tage endgültig löschen")
                    }
                }
            }
        }

        if (state.trashItems.isNotEmpty()) {
            item { HorizontalDivider() }
            items(state.trashItems, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(item.originalPath, style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatBytes(item.sizeBytes) + " · " + item.state.lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { model.restore(item.id) },
                            enabled = state.authorized && !state.busy,
                        ) {
                            Text("Wiederherstellen")
                        }
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message)
                        TextButton(onClick = model::dismissMessage) { Text("Schließen") }
                    }
                }
            }
        }
        state.error?.let { error ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::dismissMessage) { Text("Schließen") }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return DecimalFormat("0.##").format(value) + " " + units[unit.coerceAtLeast(0)]
}
