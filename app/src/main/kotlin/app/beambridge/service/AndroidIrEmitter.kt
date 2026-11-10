package app.beambridge.service

import android.content.Context
import android.hardware.ConsumerIrManager
import app.beambridge.core.ir.IrEmitter
import app.beambridge.core.ir.IrSignal
import kotlin.math.abs

/**
 * ConsumerIrManager-backed emitter. Carrier frequencies are snapped to what the hardware supports, and the pattern
 * always ends on a space ([IrSignal.forHardware]) because Qualcomm IR HALs (Poco/Redmi/Xiaomi) drop odd-length bursts.
 */
class AndroidIrEmitter(context: Context) : IrEmitter {
    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override val isAvailable: Boolean get() = manager?.hasIrEmitter() == true

    override fun transmit(signal: IrSignal) {
        val m = manager?.takeIf { it.hasIrEmitter() } ?: error("No IR emitter")
        m.transmit(snap(m, signal.frequency), signal.forHardware())
    }

    private fun snap(m: ConsumerIrManager, hz: Int): Int {
        val ranges = m.carrierFrequencies ?: return hz
        if (ranges.isEmpty() || ranges.any { hz in it.minFrequency..it.maxFrequency }) return hz
        return ranges.map { r -> if (hz < r.minFrequency) r.minFrequency else r.maxFrequency }.minByOrNull { abs(it - hz) } ?: hz
    }
}
