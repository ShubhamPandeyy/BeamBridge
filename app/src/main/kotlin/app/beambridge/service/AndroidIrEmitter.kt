package app.beambridge.service

import android.content.Context
import android.hardware.ConsumerIrManager
import app.beambridge.core.ir.IrEmitter
import app.beambridge.core.ir.IrSignal
import kotlin.math.abs

/** ConsumerIrManager-backed emitter. Carrier frequencies are snapped to what the hardware supports. */
class AndroidIrEmitter(context: Context) : IrEmitter {
    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override val isAvailable: Boolean get() = manager?.hasIrEmitter() == true

    override fun transmit(signal: IrSignal) {
        val m = manager?.takeIf { it.hasIrEmitter() } ?: error("No IR emitter")
        m.transmit(snap(m, signal.frequency), signal.pattern)
    }

    private fun snap(m: ConsumerIrManager, hz: Int): Int {
        val ranges = m.carrierFrequencies ?: return hz
        if (ranges.isEmpty() || ranges.any { hz in it.minFrequency..it.maxFrequency }) return hz
        return ranges.map { r -> if (hz < r.minFrequency) r.minFrequency else r.maxFrequency }.minByOrNull { abs(it - hz) } ?: hz
    }
}
