package site.pk187.foxplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Werte aus /workspace/fox-designsystem/tokens.json (FOX Designsystem 1.1.0). */
data class FoxTheme(
    val key: String, val label: String,
    val accent: Color, val accent2: Color, val bg: Color, val bg2: Color, val panel: Color,
    val text: Color, val muted: Color, val onAccent: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val FOX_THEMES = listOf(
    FoxTheme("foxhole", "Foxhole", c(0xff3b30), c(0xff8a80), c(0x0a0a0b), c(0x111214), c(0x111214), c(0xececee), c(0x9a9da4), c(0x000000)),
    FoxTheme("magenta", "Magenta", c(0xff2d6f), c(0xff7aa2), c(0x0a0a0b), c(0x111214), c(0x111214), c(0xececee), c(0x9a9da4), c(0x000000)),
    FoxTheme("amber", "Amber", c(0xffb000), c(0xffd36b), c(0x0a0a0b), c(0x111214), c(0x111214), c(0xececee), c(0x9a9da4), c(0x000000)),
    FoxTheme("gruen", "Grün", c(0x19d26b), c(0x7cf2ae), c(0x0a0a0b), c(0x111214), c(0x111214), c(0xececee), c(0x9a9da4), c(0x000000)),
    FoxTheme("polizei", "Polizei", c(0x4d8bff), c(0x36d6ff), c(0x050a17), c(0x0a1328), c(0x091226), c(0xe6eeff), c(0x9fb2d6), c(0x000000)),
)

fun themeFor(key: String?): FoxTheme =
    FOX_THEMES.firstOrNull { it.key == key || (key == "green" && it.key == "gruen") } ?: FOX_THEMES[0]

val LocalFox = staticCompositionLocalOf { FOX_THEMES[0] }

@Composable
fun FoxMaterial(t: FoxTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = t.accent, onPrimary = t.onAccent, secondary = t.accent2,
            background = t.bg, onBackground = t.text, surface = t.bg2, onSurface = t.text,
            surfaceVariant = t.panel, onSurfaceVariant = t.muted, outline = t.accent.copy(alpha = .24f),
        ),
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalFox provides t, content = content) }
}
