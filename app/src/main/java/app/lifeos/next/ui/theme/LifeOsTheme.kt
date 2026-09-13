package app.lifeos.next.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LifeOsLightColors = lightColorScheme(
    primary = Color(0xFF315E7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E8F7),
    onPrimaryContainer = Color(0xFF0E3447),
    secondary = Color(0xFF52616B),
    secondaryContainer = Color(0xFFDCE4E9),
    tertiary = Color(0xFF6B5C7D),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE7EDF1),
    error = Color(0xFFBA1A1A),
)

private val LifeOsDarkColors = darkColorScheme(
    primary = Color(0xFF9BCBE8),
    onPrimary = Color(0xFF00354C),
    primaryContainer = Color(0xFF174D66),
    onPrimaryContainer = Color(0xFFD1E8F7),
    secondary = Color(0xFFBEC8CE),
    secondaryContainer = Color(0xFF3B484F),
    tertiary = Color(0xFFD4BFE7),
    background = Color(0xFF101417),
    surface = Color(0xFF101417),
    surfaceVariant = Color(0xFF3F484D),
    error = Color(0xFFFFB4AB),
)

private val LifeOsShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun LifeOsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LifeOsDarkColors else LifeOsLightColors,
        typography = Typography(),
        shapes = LifeOsShapes,
        content = content,
    )
}
