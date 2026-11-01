package dev.filebe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val MonoDark = darkColorScheme(
    primary = Color(0xFFE8E8E8), onPrimary = Color(0xFF151515), primaryContainer = Color(0xFF363636), onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFCECECE), onSecondary = Color(0xFF1A1A1A), secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFE7E7E7),
    tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF202020), tertiaryContainer = Color(0xFF373737), onTertiaryContainer = Color(0xFFE2E2E2),
    background = Color(0xFF111111), onBackground = Color(0xFFF1F1F1), surface = Color(0xFF151515), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFBDBDBD), outline = Color(0xFF777777), outlineVariant = Color(0xFF393939),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)
private val MonoLight = lightColorScheme(
    primary = Color(0xFF202020), onPrimary = Color.White, primaryContainer = Color(0xFFE3E3E3), onPrimaryContainer = Color(0xFF181818),
    secondary = Color(0xFF424242), onSecondary = Color.White, secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Color(0xFF202020),
    tertiary = Color(0xFF555555), onTertiary = Color.White, tertiaryContainer = Color(0xFFE7E7E7), onTertiaryContainer = Color(0xFF242424),
    background = Color(0xFFF7F7F7), onBackground = Color(0xFF171717), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF4A4A4A), outline = Color(0xFF777777), outlineVariant = Color(0xFFD6D6D6),
    error = Color(0xFFB3261E), onError = Color.White
)

private fun TextStyle.roboto() = copy(fontFamily = FontFamily.SansSerif)
private fun googleTypography(): Typography {
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.roboto(), displayMedium = t.displayMedium.roboto(), displaySmall = t.displaySmall.roboto(),
        headlineLarge = t.headlineLarge.roboto(), headlineMedium = t.headlineMedium.roboto(), headlineSmall = t.headlineSmall.roboto(),
        titleLarge = t.titleLarge.roboto(), titleMedium = t.titleMedium.roboto(), titleSmall = t.titleSmall.roboto(),
        bodyLarge = t.bodyLarge.roboto(), bodyMedium = t.bodyMedium.roboto(), bodySmall = t.bodySmall.roboto(),
        labelLarge = t.labelLarge.roboto(), labelMedium = t.labelMedium.roboto(), labelSmall = t.labelSmall.roboto()
    )
}

@Composable fun FilebeTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) MonoDark else MonoLight, typography = googleTypography(), content = content)
}

/**
 * Low-contrast frosted surface used only for secondary cards and controls.
 * Note: deliberately no [androidx.compose.ui.draw.clip] here. `.background(color, shape)` and
 * `.border(width, color, shape)` already clip their own drawing to the rounded outline, so adding
 * `.clip()` on top only forces an extra offscreen compositing layer per instance for no visual
 * gain. With this applied to every row of a scrolling list (file rows), that's one avoidable
 * layer per visible item on every frame while scrolling.
 */
@Composable fun Modifier.glass(): Modifier {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val dark = colors.background.red < .3f
    return this
        .background(colors.surfaceVariant.copy(alpha = if (dark) .42f else .68f), shape)
        .border(1.dp, colors.outlineVariant.copy(alpha = .62f), shape)
}

@Composable fun LiquidBackground(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.surface))))
        content()
    }
}
