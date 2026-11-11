package app.beambridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.beambridge.R

/**
 * Preset palettes, each the key tones of an M3 tonal palette (Material Theme Builder baseline values):
 * light = tone 40 / 100 / 90 / 10 for primary, secondary-container 90 / 10, tertiary-container 90 / 10;
 * dark  = tone 80 / 20 / 30 / 90, secondary-container 30 / 90, tertiary-container 30 / 90.
 */
data class Preset(val id: String, val label: String, val seed: Color, val light: List<Long>, val dark: List<Long>)

val PRESETS = listOf(
    // BeamBridge's own palette (1.9 redesign): teal primary, coral tertiary. Surfaces come from [beamScheme].
    Preset("beam", "BeamBridge", Color(0xFF006A60),
        listOf(0xFF006A60, 0xFFFFFFFF, 0xFF9EF2E4, 0xFF00201C, 0xFFCCE8E2, 0xFF05201C, 0xFFFFDBCF, 0xFF380D00),
        listOf(0xFF53DBC9, 0xFF003731, 0xFF005048, 0xFF9EF2E4, 0xFF334B47, 0xFFCCE8E2, 0xFF723520, 0xFFFFDBCF)),
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

/** Full BeamBridge scheme: tertiary and the surface containers are tuned too, not just the key tones. */
private fun beamScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFF53DBC9), onPrimary = Color(0xFF003731), primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531), secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFFFB59A), onTertiary = Color(0xFF55200B), tertiaryContainer = Color(0xFF723520), onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1), surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6), outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1B), surfaceContainer = Color(0xFF1A211F),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF303635), surfaceBright = Color(0xFF343B39), surfaceDim = Color(0xFF0E1513),
    inverseSurface = Color(0xFFDDE4E1), inverseOnSurface = Color(0xFF2B3230), inversePrimary = Color(0xFF006A60),
) else lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFF9EF2E4), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF8F4C33), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFFDBCF), onTertiaryContainer = Color(0xFF380D00),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF171D1B), surface = Color(0xFFF4FBF8), onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4947), outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEEF5F2), surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7), surfaceContainerHighest = Color(0xFFDDE4E1), surfaceBright = Color(0xFFF4FBF8), surfaceDim = Color(0xFFD5DBD9),
    inverseSurface = Color(0xFF2B3230), inverseOnSurface = Color(0xFFECF2EF), inversePrimary = Color(0xFF53DBC9),
)

private fun scheme(p: Preset, dark: Boolean): ColorScheme {
    if (p.id == "beam") return beamScheme(dark)
    val t = (if (dark) p.dark else p.light).map { Color(it) }
    return if (dark) darkColorScheme(
        primary = t[0], onPrimary = t[1], primaryContainer = t[2], onPrimaryContainer = t[3],
        secondaryContainer = t[4], onSecondaryContainer = t[5], tertiaryContainer = t[6], onTertiaryContainer = t[7],
    ) else lightColorScheme(
        primary = t[0], onPrimary = t[1], primaryContainer = t[2], onPrimaryContainer = t[3],
        secondaryContainer = t[4], onSecondaryContainer = t[5], tertiaryContainer = t[6], onTertiaryContainer = t[7],
    )
}

/** M3 Expressive leans on bigger, rounder containers. */
private val M3Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ---- type: Roboto Flex (variable: weight, width, optical size), bundled so the wide "emphasized" titles work offline ----

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, width: Float = 100f) = Font(
    R.font.roboto_flex, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Body / labels: normal width. */
val RobotoFlex = FontFamily(flex(400), flex(500), flex(600), flex(700))
/** Emphasized titles: wide and heavy. */
val RobotoFlexWide = FontFamily(flex(600, 112f), flex(700, 118f), flex(800, 125f), flex(900, 130f))

private val BeamTypography: Typography = Typography().run {
    fun TextStyle.body() = copy(fontFamily = RobotoFlex)
    fun TextStyle.wide(w: Int) = copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight(w))
    copy(
        displayLarge = displayLarge.wide(800), displayMedium = displayMedium.wide(800), displaySmall = displaySmall.wide(800),
        headlineLarge = headlineLarge.wide(800), headlineMedium = headlineMedium.wide(800), headlineSmall = headlineSmall.wide(700),
        titleLarge = titleLarge.wide(700), titleMedium = titleMedium.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.body(), bodyMedium = bodyMedium.body(), bodySmall = bodySmall.body(),
        labelLarge = labelLarge.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold), labelMedium = labelMedium.body(), labelSmall = labelSmall.body(),
    )
}

/** Extra emphasized styles used for screen titles and big key labels. */
object BeamType {
    val hero = TextStyle(fontFamily = RobotoFlexWide, fontWeight = FontWeight(900), fontSize = 34.sp, lineHeight = 40.sp)
    val screenTitle = TextStyle(fontFamily = RobotoFlexWide, fontWeight = FontWeight(800), fontSize = 24.sp, lineHeight = 30.sp)
    val cardTitle = TextStyle(fontFamily = RobotoFlexWide, fontWeight = FontWeight(700), fontSize = 19.sp, lineHeight = 24.sp)
    val key = TextStyle(fontFamily = RobotoFlexWide, fontWeight = FontWeight(800), fontSize = 20.sp)
}

val dynamicColorAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * [theme]: "system" (wallpaper colours on Android 12+), "beam" (default) or another preset id. [darkMode]: auto | light | dark.
 * Material 3 Expressive look: rounder shapes, Roboto Flex with wide emphasized titles; springs are in the components.
 */
@Composable
fun BeamBridgeTheme(theme: String = "beam", darkMode: String = "auto", content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val preset = PRESETS.firstOrNull { it.id == theme }
    val colors = when {
        preset != null -> scheme(preset, dark)
        dynamicColorAvailable -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> beamScheme(dark)
    }
    // Stable Material 3 (1.4) keeps MaterialExpressiveTheme / MotionScheme internal; the expressive springs live in
    // our own components instead (keys, toolbar, D-pad, lifts).
    MaterialTheme(colorScheme = colors, shapes = M3Shapes, typography = BeamTypography, content = content)
}
