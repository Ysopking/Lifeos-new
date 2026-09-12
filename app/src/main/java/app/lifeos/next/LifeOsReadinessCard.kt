package app.lifeos.next

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.ReadinessState

data class ReadinessUiModel(
    val title: String,
    val summary: String,
    val rows: List<Pair<String, String>>,
)

fun buildReadinessUiModel(snapshot: LifeOsReadinessSnapshot): ReadinessUiModel {
    val ready = snapshot.blocks.count { it.state == ReadinessState.READY }
    val blocked = snapshot.blocks.count { it.state == ReadinessState.BLOCKED }
    val degraded = snapshot.blocks.count { it.state == ReadinessState.DEGRADED }
    return ReadinessUiModel(
        title = if (snapshot.complete) "LIFEOS A–H bereit" else "LIFEOS A–H Integritätsstatus",
        summary = "$ready bereit · $degraded eingeschränkt · $blocked blockiert",
        rows = snapshot.blocks.map { block -> block.block.name to "${block.state.name}: ${block.detail}" },
    )
}

/** Block H owner-visible readiness surface; it grants no runtime authority. */
@Composable
fun LifeOsReadinessCard(
    snapshot: LifeOsReadinessSnapshot,
    modifier: Modifier = Modifier,
) {
    val model = buildReadinessUiModel(snapshot)
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text(model.summary, style = MaterialTheme.typography.bodySmall)
            model.rows.forEach { (block, status) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Block $block", style = MaterialTheme.typography.labelMedium)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
