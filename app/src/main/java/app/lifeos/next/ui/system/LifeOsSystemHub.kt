package app.lifeos.next.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsDecisionTraceViewModel
import app.lifeos.next.LifeOsToolCenterViewModel
import app.lifeos.next.OwnerAssetReviewViewModel
import app.lifeos.next.ui.assets.OwnerAssetReviewScreen
import app.lifeos.next.ui.decision.LifeOsDecisionTraceScreen
import app.lifeos.next.ui.tools.LifeOsToolCenterScreen

private enum class SystemHubPage {
    OVERVIEW,
    WHY,
    TOOLS,
    ASSETS,
}

@Composable
fun LifeOsSystemHub(
    model: LifeOsChatViewModel,
    decisionTraceModel: LifeOsDecisionTraceViewModel,
    toolCenterModel: LifeOsToolCenterViewModel,
    assetReviewModel: OwnerAssetReviewViewModel,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SystemHubPage.OVERVIEW) }

    BackHandler(enabled = page != SystemHubPage.OVERVIEW) {
        page = SystemHubPage.OVERVIEW
    }

    when (page) {
        SystemHubPage.OVERVIEW -> Column(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Weitere Bereiche", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { page = SystemHubPage.WHY }) { Text("Warum") }
                    TextButton(onClick = { page = SystemHubPage.TOOLS }) { Text("Tools") }
                    TextButton(onClick = { page = SystemHubPage.ASSETS }) { Text("Assets") }
                }
            }
            SystemRuntimeHealthScreen(
                model = model,
                modifier = Modifier.fillMaxSize(),
            )
        }
        SystemHubPage.WHY -> TechnicalSubpage(
            title = "Warum",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            LifeOsDecisionTraceScreen(decisionTraceModel, contentModifier)
        }
        SystemHubPage.TOOLS -> TechnicalSubpage(
            title = "Tools",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            LifeOsToolCenterScreen(toolCenterModel, contentModifier)
        }
        SystemHubPage.ASSETS -> TechnicalSubpage(
            title = "Assets",
            onBack = { page = SystemHubPage.OVERVIEW },
            modifier = modifier,
        ) { contentModifier ->
            OwnerAssetReviewScreen(assetReviewModel, contentModifier)
        }
    }
}

@Composable
private fun TechnicalSubpage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("Zurück") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
        }
        content(Modifier.fillMaxSize())
    }
}
