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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LifeOsLightColors = lightColorScheme(
    primary = Color(0xFF315F82),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECF7),
    onPrimaryContainer = Color(0xFF17384D),
    secondary = Color(0xFF526676),
    secondaryContainer = Color(0xFFDCE5EB),
    tertiary = Color(0xFF695D83),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF2F6),
    error = Color(0xFFBA1A1A),
)

private val LifeOsDarkColors = darkColorScheme(
    primary = Color(0xFF9CC8E5),
    onPrimary = Color(0xFF113247),
    primaryContainer = Color(0xFF214B64),
    onPrimaryContainer = Color(0xFFDCECF7),
    secondary = Color(0xFFB9C9D5),
    secondaryContainer = Color(0xFF33434F),
    tertiary = Color(0xFFCDBDE3),
    background = Color(0xFF0E1318),
    surface = Color(0xFF151B21),
    surfaceVariant = Color(0xFF222B33),
    error = Color(0xFFFFB4AB),
)

private val LifeOsShapes = Shapes(
    small = RoundedCornerShape(LifeOsTokens.Radius.small),
    medium = RoundedCornerShape(LifeOsTokens.Radius.medium),
    large = RoundedCornerShape(LifeOsTokens.Radius.large),
)

private val LifeOsTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun LifeOsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LifeOsDarkColors else LifeOsLightColors,
        typography = LifeOsTypography,
        shapes = LifeOsShapes,
        content = content,
    )
}
