package app.beambridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Grouped list, Material 3 Expressive style: a titled section whose rows sit on one rounded tonal surface,
 * with small gaps between rows (the outer corners are big, the inner ones small).
 */
@Composable
fun MenuGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** One row of a [MenuGroup]: icon in a tonal circle, title, optional subtitle and trailing control. */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (danger) cs.error else cs.onSecondaryContainer
    val bubble = if (danger) cs.errorContainer else cs.secondaryContainer
    ListItem(
        headlineContent = { Text(title, color = if (danger) cs.error else Color.Unspecified) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(bubble), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = tint)
            }
        },
        trailingContent = trailing ?: if (chevron) ChevronTrailing else null,
        colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
    )
}

private val ChevronTrailing: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }

/** Settings-style row with a switch; the whole row toggles. */
@Composable
fun MenuSwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    MenuRow(icon, title, subtitle, trailing = { Switch(checked, onCheckedChange = onChange) }, onClick = { onChange(!checked) })
