package app.beambridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

private val Light = lightColorScheme(
    primary = Color(0xFF4F46C8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF), onPrimaryContainer = Color(0xFF1A0F63),
    secondaryContainer = Color(0xFFE3E0F2), onSecondaryContainer = Color(0xFF1A1A2C),
    tertiaryContainer = Color(0xFFFFD9E2), onTertiaryContainer = Color(0xFF3E001D),
    background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC3C0FF), onPrimary = Color(0xFF2A1F8F),
    primaryContainer = Color(0xFF3F3A8A), onPrimaryContainer = Color(0xFFE2DFFF),
    secondaryContainer = Color(0xFF454559), onSecondaryContainer = Color(0xFFE3E0F2),
    tertiaryContainer = Color(0xFF5C1135), onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF131318), surface = Color(0xFF131318),
)

/** Expressive shape scale: generously rounded, so cards and buttons feel soft and morph on press. */
private val ExpressiveShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(40.dp),
)

@Composable
fun BeamBridgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = ExpressiveShapes,
        content = content,
    )
}
