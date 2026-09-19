package app.lifeos.next.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsDetailHost(
    detailVisible: Boolean,
    modifier: Modifier = Modifier,
    mainContent: @Composable (Modifier) -> Unit,
    detailContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        when (LifeOsWindowClass.fromWidthDp(maxWidth.value)) {
            LifeOsWindowClass.EXPANDED -> Row(Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    mainContent(Modifier.fillMaxSize())
                }
                if (detailVisible) {
                    Surface(
                        modifier = Modifier
                            .width(LifeOsTokens.Layout.inspectorWidth)
                            .fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = LifeOsTokens.Elevation.raised,
                    ) {
                        detailContent(Modifier.fillMaxSize())
                    }
                }
            }

            LifeOsWindowClass.COMPACT,
            LifeOsWindowClass.MEDIUM -> {
                if (detailVisible) {
                    detailContent(Modifier.fillMaxSize())
                } else {
                    mainContent(Modifier.fillMaxSize())
                }
            }
        }
    }
}
