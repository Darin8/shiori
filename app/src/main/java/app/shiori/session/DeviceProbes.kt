package app.shiori.session

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.util.Log

object DeviceProbes {
    fun cpuMs(): Long = Process.getElapsedCpuTime()
    fun wallMs(): Long = System.nanoTime() / 1_000_000

    /** "battery=NN% current=N.NmA" (BATTERY_PROPERTY_CURRENT_NOW is microamps; the sign depends on the device). */
    fun battery(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return "battery=$level% current=${"%.1f".format(ua / 1000.0)}mA"
    }

    fun costMeter(context: Context) = CostMeter(
        log = { Log.i("Shiori", it) },
        cpuMs = ::cpuMs,
        wallMs = ::wallMs,
        battery = { battery(context) },
    )
}
