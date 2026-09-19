package app.lifeos.next.kernel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import java.time.Instant

/** Reads only local device state; it performs no network access and requires no new permission. */
internal class AndroidHardwareStateReader(
    context: Context,
    private val now: () -> Instant = Instant::now,
    private val cpuLoadSampler: AndroidCpuLoadSampler = AndroidCpuLoadSampler(),
) {
    private val appContext = context.applicationContext

    fun read(): HardwareStateSnapshot {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(appContext.filesDir.absolutePath)
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val batteryLevel = batteryFraction(battery)
        val charging = chargingState(battery)
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuObservation = cpuLoadSampler.sample(availableProcessors)
        val memoryPressure = if (memory.totalMem > 0L) {
            (1.0 - memory.availMem.toDouble() / memory.totalMem.toDouble()).coerceIn(0.0, 1.0)
        } else {
            null
        }

        return HardwareStateSnapshot(
            observedAt = now(),
            availableProcessors = availableProcessors,
            batteryFraction = batteryLevel,
            charging = charging,
            thermalState = thermalState(powerManager),
            cpuLoadFraction = cpuObservation?.loadFraction,
            processCpuLoadFraction = cpuObservation?.loadFraction,
            memoryPressureFraction = memoryPressure,
            ioPressureFraction = null,
            acceleratorFingerprint = null,
            availableMemoryBytes = memory.availMem,
            totalMemoryBytes = memory.totalMem,
            availableStorageBytes = storage.availableBytes,
            totalStorageBytes = storage.totalBytes,
        )
    }

    private fun batteryFraction(intent: Intent?): Double? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level.toDouble() / scale.toDouble()).coerceIn(0.0, 1.0)
        } else {
            null
        }
    }

    private fun chargingState(intent: Intent?): Boolean? {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (status < 0) return null
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun thermalState(powerManager: PowerManager): HardwareThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return HardwareThermalState.UNKNOWN
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> HardwareThermalState.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> HardwareThermalState.FAIR
            PowerManager.THERMAL_STATUS_MODERATE -> HardwareThermalState.FAIR
            PowerManager.THERMAL_STATUS_SEVERE -> HardwareThermalState.SERIOUS
            PowerManager.THERMAL_STATUS_CRITICAL -> HardwareThermalState.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> HardwareThermalState.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> HardwareThermalState.SHUTDOWN
            else -> HardwareThermalState.UNKNOWN
        }
    }
}
