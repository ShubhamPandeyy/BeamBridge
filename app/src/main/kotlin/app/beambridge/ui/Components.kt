package app.beambridge.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    "HOME" -> Icons.Rounded.Home
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

/** Icon in a soft tonal blob. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.36f)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Expressive pill button: tap sends, long-press opens settings, trailing icon copies the URL.
 * Corners tighten with a spring while pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandTile(
    name: String,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 10.dp else 26.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "corner")
    val container = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val icon = buttonIcon(name)
    Row(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(container)
            .combinedClickable(interactionSource = source, indication = ripple(), onClick = onPress, onLongClick = onLongPress)
            .padding(start = 18.dp, top = 4.dp, bottom = 4.dp, end = if (onCopy == null) 18.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = content)
        Text(prettyName(name), color = content, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 10.dp))
        if (onCopy != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ContentCopy, "Copy URL", Modifier.size(18.dp), tint = content.copy(alpha = 0.75f)) }
        }
    }
}
