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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LifeOsLightColors = lightColorScheme(
    primary = Color(0xFF0D6878),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBCECF5),
    onPrimaryContainer = Color(0xFF063640),
    secondary = Color(0xFF53666D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E7EB),
    onSecondaryContainer = Color(0xFF26373C),
    tertiary = Color(0xFF526B5D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4E9D9),
    onTertiaryContainer = Color(0xFF233A2D),
    background = Color(0xFFF4F7F8),
    onBackground = Color(0xFF182023),
    surface = Color(0xFFFCFEFF),
    onSurface = Color(0xFF182023),
    surfaceVariant = Color(0xFFE6EEF0),
    onSurfaceVariant = Color(0xFF536166),
    outline = Color(0xFF75858A),
    outlineVariant = Color(0xFFC6D3D6),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val LifeOsDarkColors = darkColorScheme(
    primary = Color(0xFF76D7E9),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0B4F5C),
    onPrimaryContainer = Color(0xFFBCECF5),
    secondary = Color(0xFFB9CBD0),
    onSecondary = Color(0xFF243438),
    secondaryContainer = Color(0xFF35484D),
    onSecondaryContainer = Color(0xFFD8E7EB),
    tertiary = Color(0xFFB8D6BF),
    onTertiary = Color(0xFF23372B),
    tertiaryContainer = Color(0xFF394F40),
    onTertiaryContainer = Color(0xFFD4E9D9),
    background = Color(0xFF070B0D),
    onBackground = Color(0xFFE1E8EA),
    surface = Color(0xFF0C1215),
    onSurface = Color(0xFFE1E8EA),
    surfaceVariant = Color(0xFF182226),
    onSurfaceVariant = Color(0xFFBAC8CC),
    outline = Color(0xFF839297),
    outlineVariant = Color(0xFF334247),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LifeOsTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val LifeOsShapes = Shapes(
    small = RoundedCornerShape(LifeOsTokens.Radius.small),
    medium = RoundedCornerShape(LifeOsTokens.Radius.medium),
    large = RoundedCornerShape(LifeOsTokens.Radius.large),
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
