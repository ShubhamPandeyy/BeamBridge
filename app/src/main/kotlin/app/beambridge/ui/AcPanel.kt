package app.beambridge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.core.data.AcState
import app.beambridge.core.data.SavedDevice
import kotlin.math.atan2
import kotlin.math.roundToInt

private const val START = 135f   // dial arc starts bottom-left …
private const val SWEEP = 270f   // … and sweeps clockwise to bottom-right

/**
 * AC controls: circular temperature dial (drag the ring) + slider, mode and fan chips, power.
 * Shows the saved state and, separately, the last frame actually sent: IR is one-way, so neither is confirmed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcPanel(vm: MainViewModel, device: SavedDevice) {
    val remote = vm.acRemote(device)
    val state = device.acState ?: AcState()
    val range = remote?.temperatures ?: 16..30
    val history by vm.history.collectAsStateWithLifecycle()
    val last = remember(history, device.id) { vm.lastSent(device.id) }
    var preview by remember(state.temperature) { mutableFloatStateOf(state.temperature.toFloat()) }
    val cs = MaterialTheme.colorScheme

    fun setTemp(t: Int) {
        val target = t.coerceIn(range)
        if (target != state.temperature) vm.setAcTemperature(device.id, target)
    }

    Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val frac by animateFloatAsState((preview - range.first) / (range.last - range.first).coerceAtLeast(1).toFloat(), label = "dial")
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(range, state.power) {
                        fun valueAt(p: Offset): Float {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            var a = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                            a = (a - START + 360f) % 360f
                            val f = (a / SWEEP).coerceIn(0f, 1f)
                            return range.first + f * (range.last - range.first)
                        }
                        detectDragGestures(onDragEnd = { setTemp(preview.roundToInt()) }) { ch, _ -> ch.consume(); preview = valueAt(ch.position) }
                    },
                ) {
                    val stroke = 18.dp.toPx()
                    val inset = stroke / 2 + 6.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(cs.surfaceVariant, START, SWEEP, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (state.power) drawArc(cs.primary, START, SWEEP * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.power) "${preview.roundToInt()}°" else "Off", style = MaterialTheme.typography.displayLarge, color = if (state.power) cs.onSurface else cs.onSurfaceVariant)
                    if (state.power) Text("${prettyName(state.mode)} · fan ${state.fan}", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { vm.press(device.id, "TEMP_DOWN") }, Modifier.size(52.dp), enabled = state.power) { Icon(Icons.Rounded.Remove, "Cooler") }
                Slider(
                    preview, { preview = it }, Modifier.weight(1f), enabled = state.power,
                    valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
                    onValueChangeFinished = { setTemp(preview.roundToInt()) },
                )
                FilledTonalIconButton(onClick = { vm.press(device.id, "TEMP_UP") }, Modifier.size(52.dp), enabled = state.power) { Icon(Icons.Rounded.Add, "Warmer") }
            }
            Button(
                onClick = { vm.press(device.id, if (state.power) "POWER_OFF" else "POWER_ON") }, Modifier.fillMaxWidth().height(52.dp),
                colors = if (state.power) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            ) { Icon(Icons.Rounded.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text(if (state.power) "Turn off" else "Turn on") }

            Text("Mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                remote?.modes.orEmpty().forEach { mode ->
                    FilterChip(
                        selected = state.power && state.mode == mode, onClick = { vm.press(device.id, "MODE_${mode.uppercase()}") },
                        label = { Text(prettyName(mode)) },
                        leadingIcon = { Icon(modeIcon(mode), null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (remote?.fanSpeeds.orEmpty().isNotEmpty()) {
                Text("Fan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val fans = remote!!.fanSpeeds
                    fans.forEachIndexed { i, fan ->
                        SegmentedButton(selected = state.fan == fan, onClick = { vm.press(device.id, "FAN_${fan.uppercase()}") }, shape = SegmentedButtonDefaults.itemShape(i, fans.size)) { Text(prettyName(fan), maxLines = 1) }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Saved state: " + if (state.power) "${prettyName(state.mode)} ${state.temperature}° fan ${state.fan}" else "Off", style = MaterialTheme.typography.bodySmall)
                Text(
                    last?.let { "Last sent: ${prettyName(it.button)} · ${android.text.format.DateUtils.getRelativeTimeSpanString(it.time)}" } ?: "Nothing sent yet",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("IR is one-way, so the AC can't confirm it changed. If it's out of sync, set it once with the real remote.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}


private fun modeIcon(mode: String) = when (mode) {
    "cool" -> Icons.Rounded.AcUnit
    "heat" -> Icons.Rounded.WbSunny
    "dry" -> Icons.Rounded.WaterDrop
    "fan" -> Icons.Rounded.Air
    else -> Icons.Rounded.Autorenew
}
