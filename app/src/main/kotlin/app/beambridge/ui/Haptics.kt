package app.beambridge.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Key-press vibration with the strength chosen in Settings → Look & feel → Vibration ("off", "light", "medium", "strong").
 * Uses the phone's tuned click effects where available (Android 10+), otherwise a short pulse whose length (and
 * strength, if the motor supports it) follows the level.
 */
class BeamHaptics(private val vibrator: Vibrator?, val level: String) {
    private val rank = LEVELS.indexOf(level).coerceAtLeast(0)

    /** A key was sent. */
    fun key() = buzz(rank)
    /** The finger slid onto another key (lighter than a press). */
    fun tick() = buzz(rank - 1, min = 1)
    /** Long-press / entering edit mode (a bit stronger than a press). */
    fun long() = buzz(rank + 1)

    /** Same motor, another strength (to preview a level in Settings). */
    fun withLevel(level: String) = BeamHaptics(vibrator, level)

    private fun buzz(r: Int, min: Int = 1) {
        val v = vibrator ?: return
        if (rank == 0 || !v.hasVibrator()) return
        val level = r.coerceIn(min, 3)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(when (level) { 1 -> VibrationEffect.EFFECT_TICK; 2 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK }))
            } else {
                val ms = longArrayOf(0, 10, 18, 30)[level]
                val amp = if (v.hasAmplitudeControl()) intArrayOf(0, 70, 150, 255)[level] else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            }
        }
    }

    companion object {
        val LEVELS = listOf("off", "light", "medium", "strong")
        val LABELS = mapOf("off" to "Off", "light" to "Light", "medium" to "Medium", "strong" to "Strong")

        fun vibrator(ctx: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }
}

val LocalBeamHaptics = staticCompositionLocalOf { BeamHaptics(null, "off") }

@Composable
fun ProvideBeamHaptics(level: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val haptics = remember(level) { BeamHaptics(BeamHaptics.vibrator(ctx), level) }
    CompositionLocalProvider(LocalBeamHaptics provides haptics, content = content)
}
