package app.lifeos.next.ui.memory

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.lifeos.core.model.PhotonId
import app.lifeos.next.ui.components.PhotonImagePreviewState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MemorySourceDetailsDialog(
    sourceId: PhotonId,
    source: MemorySourceUi?,
    loadImagePreview: suspend (PhotonId) -> PhotonImagePreviewState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        title = { Text("Gedächtnisquelle") },
        text = {
            if (source == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quelle nicht verfügbar", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Das referenzierte Quell-Photon ${sourceId.value} ist im aktuellen autoritativen Speicher nicht auflösbar. LIFEOS ergänzt keinen Ersatzinhalt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (source.isImage) {
                        MemoryImagePreview(
                            photonId = source.photonId,
                            loadImagePreview = loadImagePreview,
                        )
                    } else {
                        Text(source.content, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        when {
                            source.isNew -> "Status: Neu · noch nicht in Langzeitprojektion"
                            source.stage != null -> "Gedächtnisstufe: ${source.stage.germanLabel()}"
                            else -> "Gedächtnisstufe: nicht projiziert"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "Zeit: ${MEMORY_TIME_FORMAT.format(source.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Konfidenz: ${"%.0f".format(source.confidence * 100.0)} %",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Quelle: ${source.source} · Akteur: ${source.actor}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Lineage: ${source.parentCount} Parent(s) · ${source.relationCount} Relation(en)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (source.tags.isNotEmpty()) {
                        Text(
                            "Tags: ${source.tags.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "Photon: ${source.photonId.value}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    )
}

@Composable
fun MemoryImagePreview(
    photonId: PhotonId,
    loadImagePreview: suspend (PhotonId) -> PhotonImagePreviewState,
    modifier: Modifier = Modifier,
) {
    val previewState by produceState<PhotonImagePreviewState>(
        initialValue = PhotonImagePreviewState.Loading,
        key1 = photonId.value,
    ) {
        value = loadImagePreview(photonId)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (val current = previewState) {
            PhotonImagePreviewState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Lokales Bild wird entschlüsselt …", style = MaterialTheme.typography.bodySmall)
            }
            is PhotonImagePreviewState.Ready -> {
                val preview = current.preview
                Image(
                    bitmap = preview.bitmap.asImageBitmap(),
                    contentDescription = "Lokales Bild aus dem LIFEOS-Gedächtnis",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(preview.width.toFloat() / preview.height.toFloat()),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    "${preview.width}×${preview.height} · ${preview.rendererId}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            is PhotonImagePreviewState.Failed -> {
                Text(
                    "Bild nicht verfügbar",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(current.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val MEMORY_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
