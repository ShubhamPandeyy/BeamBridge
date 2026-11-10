package app.beambridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Preset palettes, each the key tones of an M3 tonal palette (Material Theme Builder baseline values):
 * light = tone 40 / 100 / 90 / 10 for primary, secondary-container 90 / 10, tertiary-container 90 / 10;
 * dark  = tone 80 / 20 / 30 / 90, secondary-container 30 / 90, tertiary-container 30 / 90.
 */
data class Preset(val id: String, val label: String, val seed: Color, val light: List<Long>, val dark: List<Long>)

val PRESETS = listOf(
    Preset("purple", "Purple", Color(0xFF6750A4),
        listOf(0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D, 0xFFE8DEF8, 0xFF1D192B, 0xFFFFD8E4, 0xFF31111D),
        listOf(0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF, 0xFF4A4458, 0xFFE8DEF8, 0xFF633B48, 0xFFFFD8E4)),
    Preset("blue", "Blue", Color(0xFF0061A4),
        listOf(0xFF0061A4, 0xFFFFFFFF, 0xFFD1E4FF, 0xFF001D36, 0xFFD7E3F7, 0xFF101C2B, 0xFFF2DAFF, 0xFF251431),
        listOf(0xFF9ECAFF, 0xFF003258, 0xFF00497D, 0xFFD1E4FF, 0xFF3B4858, 0xFFD7E3F7, 0xFF523F5F, 0xFFF2DAFF)),
    Preset("green", "Green", Color(0xFF386A20),
        listOf(0xFF386A20, 0xFFFFFFFF, 0xFFB8F397, 0xFF042100, 0xFFD7E8CD, 0xFF121F0E, 0xFFBBEBEC, 0xFF002021),
        listOf(0xFF9CD67D, 0xFF0C3900, 0xFF205107, 0xFFB8F397, 0xFF3C4B37, 0xFFD7E8CD, 0xFF1E4E4E, 0xFFBBEBEC)),
    Preset("red", "Red", Color(0xFFB3261E),
        listOf(0xFFB3261E, 0xFFFFFFFF, 0xFFFFDAD5, 0xFF410001, 0xFFFFDAD5, 0xFF2C1512, 0xFFFBDFA6, 0xFF251A00),
        listOf(0xFFFFB4A9, 0xFF690002, 0xFF930006, 0xFFFFDAD5, 0xFF5D3F3B, 0xFFFFDAD5, 0xFF5A4300, 0xFFFBDFA6)),
    Preset("orange", "Orange", Color(0xFF8B5000),
        listOf(0xFF8B5000, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2C1600, 0xFFF2DFD1, 0xFF251911, 0xFFD0E8C8, 0xFF0C200A),
        listOf(0xFFFFB870, 0xFF4A2800, 0xFF693C00, 0xFFFFDCBE, 0xFF51443A, 0xFFF2DFD1, 0xFF364B32, 0xFFD0E8C8)),
    Preset("teal", "Teal", Color(0xFF006A6A),
        listOf(0xFF006A6A, 0xFFFFFFFF, 0xFF6FF7F6, 0xFF002020, 0xFFCCE8E7, 0xFF051F1F, 0xFFD6E3FF, 0xFF0D1C32),
        listOf(0xFF4CDADA, 0xFF003737, 0xFF004F4F, 0xFF6FF7F6, 0xFF324B4B, 0xFFCCE8E7, 0xFF3B4664, 0xFFD6E3FF)),
)

private fun scheme(p: Preset, dark: Boolean): ColorScheme {
    val t = (if (dark) p.dark else p.light).map { Color(it) }
    return if (dark) darkColorScheme(
        primary = t[0], onPrimary = t[1], primaryContainer = t[2], onPrimaryContainer = t[3],
        secondaryContainer = t[4], onSecondaryContainer = t[5], tertiaryContainer = t[6], onTertiaryContainer = t[7],
    ) else lightColorScheme(
        primary = t[0], onPrimary = t[1], primaryContainer = t[2], onPrimaryContainer = t[3],
        secondaryContainer = t[4], onSecondaryContainer = t[5], tertiaryContainer = t[6], onTertiaryContainer = t[7],
    )
}

/** M3 shape scale (extra-small 4 → extra-large 28), with a rounder large/extra-large for the remote surfaces. */
private val M3Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val dynamicColorAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** [theme]: "system" (wallpaper colours on Android 12+) or a preset id. [darkMode]: auto | light | dark. */
@Composable
fun BeamBridgeTheme(theme: String = "system", darkMode: String = "auto", content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val preset = PRESETS.firstOrNull { it.id == theme }
    val colors = when {
        preset != null -> scheme(preset, dark)
        dynamicColorAvailable -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> scheme(PRESETS.first(), dark)
    }
    MaterialTheme(colorScheme = colors, shapes = M3Shapes, content = content)
}
