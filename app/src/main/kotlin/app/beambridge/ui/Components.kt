package app.beambridge.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.beambridge.core.data.DeviceType

fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.TV -> Icons.Rounded.Tv
    DeviceType.AC -> Icons.Rounded.AcUnit
    DeviceType.SET_TOP_BOX -> Icons.Rounded.SettingsInputAntenna
    DeviceType.OTHER -> Icons.Rounded.Dialpad
}

/** Icon for well-known button names, or null when the label should be shown instead. */
fun buttonIcon(name: String): ImageVector? = when (name.uppercase()) {
    "POWER", "POWER_ON", "POWER_OFF", "POWER_TOGGLE" -> Icons.Rounded.PowerSettingsNew
    "VOL_UP" -> Icons.Rounded.VolumeUp
    "VOL_DOWN" -> Icons.Rounded.VolumeDown
    "VOL_MUTE", "MUTE" -> Icons.Rounded.VolumeOff
    "CH_UP", "UP" -> Icons.Rounded.KeyboardArrowUp
    "CH_DOWN", "DOWN" -> Icons.Rounded.KeyboardArrowDown
    "LEFT" -> Icons.Rounded.KeyboardArrowLeft
    "RIGHT" -> Icons.Rounded.KeyboardArrowRight
    "OK", "SELECT" -> Icons.Rounded.RadioButtonChecked
    "BACK" -> Icons.AutoMirrored.Rounded.ArrowBack
    "MENU" -> Icons.Rounded.Menu
    "HOME", "TCL_HOME" -> Icons.Rounded.Home
    "EXIT" -> Icons.Rounded.Close
    "INFO" -> Icons.Rounded.Info
    "GUIDE", "EPG" -> Icons.Rounded.CalendarViewDay
    "REWIND" -> Icons.Rounded.FastRewind
    "FAST_FORWARD", "FORWARD" -> Icons.Rounded.FastForward
    "NEXT" -> Icons.Rounded.SkipNext
    "PREVIOUS", "PREV" -> Icons.Rounded.SkipPrevious
    "RECORD" -> Icons.Rounded.FiberManualRecord
    "SETTINGS" -> Icons.Rounded.Settings
    "INPUT" -> Icons.AutoMirrored.Rounded.Input
    "SUBTITLE", "SUBTITLES" -> Icons.Rounded.Subtitles
    "SLEEP" -> Icons.Rounded.Bedtime
    "YOUTUBE", "NETFLIX", "PRIME" -> Icons.Rounded.SmartDisplay
    "SOURCE" -> Icons.AutoMirrored.Rounded.Input
    "PLAY" -> Icons.Rounded.PlayArrow
    "PAUSE" -> Icons.Rounded.Pause
    "STOP" -> Icons.Rounded.Stop
    "TEMP_UP" -> Icons.Rounded.Add
    "TEMP_DOWN" -> Icons.Rounded.Remove
    else -> null
}

fun prettyName(name: String): String = name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Small rounded status chip with a coloured dot. */
@Composable
fun StatusPill(text: String, good: Boolean, modifier: Modifier = Modifier) {
    val container = if (good) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (good) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(content, CircleShape))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Icon in a soft tonal blob. Pass an expressive [shape] (see [BeamShapes]) for identity badges. */
@Composable
fun IconBadge(
    icon: ImageVector, size: Dp = 48.dp, modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(size * 0.36f),
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.46f), tint = content)
    }
}

/**
 * Wavy "polar" shape in the Material 3 Expressive style: a circle whose radius ripples [lobes] times around the edge
 * ([depth] = how deep the ripples go, 0..1). 9 shallow lobes = scalloped cookie, 4 deep lobes = clover, and so on.
 * Drawn here (not with Material's experimental MaterialShapes, which the stable 1.4 library keeps internal).
 */
class PolarShape(private val lobes: Int, private val depth: Float, private val rotationDeg: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cx = size.width / 2f; val cy = size.height / 2f; val r = minOf(cx, cy)
        val steps = 144
        val rot = Math.toRadians(rotationDeg.toDouble())
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val k = (1f - depth) + depth * (0.5f + 0.5f * cos(lobes * (t + rot)).toFloat())
            val x = cx + r * k * cos(t).toFloat(); val y = cy + r * k * sin(t).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Identity shapes: Power and OK are scalloped cookies; each device type has its own badge shape. */
object BeamShapes {
    val power: Shape = PolarShape(9, 0.12f)
    val ok: Shape = PolarShape(9, 0.12f)
    val iphone: Shape = PolarShape(4, 0.36f)
    val bridge: Shape = PolarShape(8, 0.08f)
    val scene: Shape = PolarShape(12, 0.14f)
    val coffee: Shape = PolarShape(9, 0.12f)

    fun device(type: DeviceType): Shape = when (type) {
        DeviceType.TV -> PolarShape(4, 0.36f)
        DeviceType.AC -> PolarShape(4, 0.18f, 45f)
        DeviceType.SET_TOP_BOX -> PolarShape(12, 0.14f)
        DeviceType.OTHER -> PolarShape(8, 0.08f)
    }
}

/** Busy / listening indicator: a cookie that slowly spins and breathes (expressive stand-in for LoadingIndicator). */
@Composable
fun ShapeSpinner(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "spinner")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle")
    val pulse by t.animateFloat(0.78f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(modifier.graphicsLayer { rotationZ = angle; scaleX = pulse; scaleY = pulse }.clip(BeamShapes.scene).background(color))
}

/** Device badge: its type's shape and icon; containers rotate through primary / secondary / tertiary by type. */
@Composable
fun DeviceBadge(type: DeviceType, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (type) {
        DeviceType.TV -> cs.primaryContainer to cs.onPrimaryContainer
        DeviceType.AC -> cs.secondaryContainer to cs.onSecondaryContainer
        DeviceType.SET_TOP_BOX -> cs.tertiaryContainer to cs.onTertiaryContainer
        DeviceType.OTHER -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    IconBadge(deviceIcon(type), size, modifier, shape = BeamShapes.device(type), container = container, content = content)
}

/**
 * Icon-only remote key. Tap sends, long-press opens the key menu. Shape springs tighter while pressed.
 * Keys without a known icon show a short text label instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteKey(
    name: String,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = buttonIcon(name),
    round: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val rest = if (round) size / 2 else 20.dp
    val corner by animateDpAsState(if (pressed) 12.dp else rest, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "corner")
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .combinedClickable(interactionSource = source, indication = ripple(), onClick = onPress, onLongClick = onLongPress, onClickLabel = prettyName(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, prettyName(name), Modifier.size(size * 0.42f), tint = content)
        else Text(shortLabel(name), color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Compact label for keys with no icon: "NETFLIX" -> "Netflix", "KEY_1" -> "1". */
fun shortLabel(name: String): String {
    val n = name.uppercase().removePrefix("KEY_").removePrefix("NUM_").removePrefix("DIGIT_")
    if (n.length <= 3) return n
    val p = prettyName(n)
    return if (p.length > 8) p.take(7) + "…" else p
}
