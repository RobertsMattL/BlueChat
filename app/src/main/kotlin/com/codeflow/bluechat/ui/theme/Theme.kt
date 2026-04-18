package com.codeflow.bluechat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    // Primary colors
    primary = LightSilver,
    onPrimary = Black,
    primaryContainer = LightGray,
    onPrimaryContainer = White,

    // Secondary colors
    secondary = Silver,
    onSecondary = Black,
    secondaryContainer = MediumGray,
    onSecondaryContainer = White,

    // Tertiary colors
    tertiary = Gray,
    onTertiary = White,
    tertiaryContainer = LightGray,
    onTertiaryContainer = White,

    // Background colors
    background = Black,
    onBackground = White,

    // Surface colors
    surface = DarkGray,
    onSurface = White,
    surfaceVariant = MediumGray,
    onSurfaceVariant = LightSilver,

    // Other colors
    error = Color(0xFFCF6679),
    onError = Black,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Silver,
    outlineVariant = Gray,
    scrim = Black,
    inverseSurface = White,
    inverseOnSurface = Black,
    inversePrimary = Gray,
    surfaceTint = LightSilver
)

@Composable
fun BlueChatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
