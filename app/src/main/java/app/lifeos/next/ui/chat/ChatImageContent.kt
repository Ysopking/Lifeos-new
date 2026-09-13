package app.lifeos.next.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.lifeos.core.model.Photon

@Composable
fun ChatImageContent(
    photon: Photon,
    loadPreview: suspend (Photon) -> ChatImagePreviewState,
    modifier: Modifier = Modifier,
) {
    val previewState by produceState<ChatImagePreviewState>(
        initialValue = ChatImagePreviewState.Loading,
        key1 = photon.id.value,
        key2 = photon.revision,
    ) {
        value = loadPreview(photon)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            when (val current = previewState) {
                ChatImagePreviewState.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Lokales Bild wird entschlüsselt …",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is ChatImagePreviewState.Ready -> {
                    val preview = current.preview
                    val transformed = "transformed" in photon.tags
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = if (transformed) {
                            "Lokal bearbeitetes LIFEOS-Bild"
                        } else {
                            "Lokal erzeugtes LIFEOS-Bild"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(preview.width.toFloat() / preview.height.toFloat()),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        "${if (transformed) "Offline bearbeitet" else "Offline erzeugt"} · " +
                            "${preview.width}×${preview.height} · ${preview.rendererId}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is ChatImagePreviewState.Failed -> {
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
}
