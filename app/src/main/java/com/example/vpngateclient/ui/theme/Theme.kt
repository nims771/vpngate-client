package com.example.vpngateclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

val Green = Color(0xFF22C55E)
val GreenDark = Color(0xFF15803D)
val Amber = Color(0xFFF59E0B)
val Idle = Color(0xFF3A4440)

private val Scheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF04140A),
    primaryContainer = Color(0xFF123A22),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF04140A),
    secondaryContainer = Color(0xFF1E3A2A),
    onSecondaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF0B0F0D),
    onBackground = Color(0xFFE8EDEA),
    surface = Color(0xFF0B0F0D),
    onSurface = Color(0xFFE8EDEA),
    surfaceVariant = Color(0xFF1A211D),
    onSurfaceVariant = Color(0xFF9AA5A0),
    surfaceContainerLowest = Color(0xFF080B09),
    surfaceContainerLow = Color(0xFF121714),
    surfaceContainer = Color(0xFF161C19),
    surfaceContainerHigh = Color(0xFF1B221E),
    surfaceContainerHighest = Color(0xFF222A26),
    outline = Color(0xFF2E3833),
    outlineVariant = Color(0xFF242C28),
    error = Color(0xFFF87171),
    onError = Color(0xFF2B0505),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFECACA),
)

private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Всегда тёмная тема с зелёным акцентом. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
