package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.AppThemeMode

private val LightColors =
  lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Slate900,
    secondary = Sky600,
    onSecondary = Color.White,
    secondaryContainer = Sky100,
    onSecondaryContainer = Slate900,
    tertiary = Emerald600,
    onTertiary = Color.White,
    tertiaryContainer = Emerald100,
    onTertiaryContainer = Slate900,
    error = Rose600,
    onError = Color.White,
    errorContainer = Rose100,
    onErrorContainer = Slate900,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    outline = Slate200,
    outlineVariant = Slate200,
  )

private val DarkColors =
  darkColorScheme(
    primary = Cyan400,
    onPrimary = Slate950,
    primaryContainer = Slate800,
    onPrimaryContainer = Cyan400,
    secondary = Indigo500,
    onSecondary = Color.White,
    secondaryContainer = Slate850,
    onSecondaryContainer = Slate100,
    tertiary = Emerald400,
    onTertiary = Slate950,
    tertiaryContainer = Slate800,
    onTertiaryContainer = Emerald400,
    error = Rose400,
    onError = Slate950,
    errorContainer = Slate800,
    onErrorContainer = Rose400,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate850,
    onSurfaceVariant = Slate400,
    outline = Slate700,
    outlineVariant = Slate800,
  )

private val HighContrastColors =
  darkColorScheme(
    primary = HcCyan,
    onPrimary = HcBlack,
    primaryContainer = HcBlack,
    onPrimaryContainer = HcCyan,
    secondary = HcMagenta,
    onSecondary = HcBlack,
    secondaryContainer = HcBlack,
    onSecondaryContainer = HcMagenta,
    tertiary = HcYellow,
    onTertiary = HcBlack,
    error = Color(0xFFFF5555),
    onError = HcBlack,
    background = HcBlack,
    onBackground = HcWhite,
    surface = HcBlack,
    onSurface = HcWhite,
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = HcWhite,
    outline = HcWhite,
    outlineVariant = HcCyan,
  )

val LinkDrawShapes =
  Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
  )

@Composable
fun LinkDrawTheme(
  themeMode: AppThemeMode = AppThemeMode.LIGHT,
  content: @Composable () -> Unit,
) {
  val colors =
    when (themeMode) {
      AppThemeMode.LIGHT -> LightColors
      AppThemeMode.DARK -> DarkColors
      AppThemeMode.HIGH_CONTRAST -> HighContrastColors
    }

  MaterialTheme(
    colorScheme = colors,
    typography = LinkDrawTypography,
    shapes = LinkDrawShapes,
    content = content,
  )
}
