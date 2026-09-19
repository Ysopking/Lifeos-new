package app.lifeos.next.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsContentFrame(
    modifier: Modifier = Modifier,
    maxWidth: Dp = LifeOsTokens.Layout.readingMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (this.maxWidth >= LifeOsTokens.Layout.workspaceMaxWidth) {
            LifeOsTokens.Layout.wideHorizontalPadding
        } else {
            LifeOsTokens.Layout.compactHorizontalPadding
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                content = content,
            )
        }
    }
}
