package com.ikar.assistant.diagnostics

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock

data class BatterySample(
    val elapsedRealtimeMs: Long,
    val batteryPercent: Int,
    val currentNowMicroAmps: Int?,
    val chargeCounterMicroAmpHours: Int?,
    val processPssKb: Int
)

/**
 * Lightweight, user-started sampling. A phone-specific result is valid only
 * after this session is run on the actual device; unsupported battery sensors
 * are reported as null rather than fabricated.
 */
class BatteryStabilitySession(context: Context) {
    private val appContext = context.applicationContext
    private val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val startedAt = SystemClock.elapsedRealtime()
    private val samples = mutableListOf<BatterySample>()
    private var stopped = false

    @Synchronized
    fun sample(): BatterySample {
        check(!stopped) { "La session est terminée." }
        val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE }
        val charge = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Int.MIN_VALUE }
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        return BatterySample(SystemClock.elapsedRealtime(), percent, current, charge, memory)
            .also { samples += it }
    }

    @Synchronized
    fun finish(): List<BatterySample> {
        stopped = true
        return samples.toList()
    }

    fun durationMs(): Long = SystemClock.elapsedRealtime() - startedAt
}
