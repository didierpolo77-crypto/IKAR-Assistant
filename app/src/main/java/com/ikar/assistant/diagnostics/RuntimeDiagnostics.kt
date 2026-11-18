package com.ikar.assistant.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug

data class RuntimeSnapshot(
    val supportedAbis: List<String>,
    val is32BitProcess: Boolean,
    val totalMemoryMb: Long,
    val appPssKb: Int,
    val lowRamDevice: Boolean
)

object RuntimeDiagnostics {
    fun capture(context: Context): RuntimeSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)
        val abis = Build.SUPPORTED_ABIS.toList()
        val processIs64Bit = if (Build.VERSION.SDK_INT >= 23) android.os.Process.is64Bit() else false
        return RuntimeSnapshot(
            supportedAbis = abis,
            is32BitProcess = !processIs64Bit,
            totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L),
            appPssKb = processMemory.totalPss,
            lowRamDevice = activityManager.isLowRamDevice
        )
    }
}
