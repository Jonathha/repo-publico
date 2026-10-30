package com.example.data.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.PowerManager
import java.util.Locale

data class RamDiagnostic(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usedPercentage: Int,
    val isLowMemory: Boolean,
    val formattedSummary: String
)

data class StorageDiagnostic(
    val usedPercentage: Int,
    val summary: String
)

data class ThermalDiagnostic(
    val batteryTempCelsius: Float?,
    val thermalStatusText: String?,
    val isSevereOrCritical: Boolean,
    val formattedSummary: String
)

data class DeviceSpec(
    val brand: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val hardware: String
)

object DeviceDiagnostics {

    fun readRam(context: Context): RamDiagnostic? {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)

            val total = memInfo.totalMem
            val avail = memInfo.availMem
            val used = total - avail
            val percent = if (total > 0) ((used * 100) / total).toInt() else 0

            val totalGb = total.toDouble() / (1024 * 1024 * 1024)
            val usedGb = used.toDouble() / (1024 * 1024 * 1024)

            RamDiagnostic(
                totalBytes = total,
                availableBytes = avail,
                usedBytes = used,
                usedPercentage = percent,
                isLowMemory = memInfo.lowMemory,
                formattedSummary = String.format(Locale.US, "%.1f GB / %.1f GB (%d%%)", usedGb, totalGb, percent)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun readThermals(context: Context): ThermalDiagnostic {
        var tempCelsius: Float? = null
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (rawTemp > 0) {
                tempCelsius = rawTemp / 10.0f
            }
        } catch (_: Exception) {
        }

        var statusText: String? = null
        var isSevere = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = powerManager?.currentThermalStatus
                if (status != null) {
                    when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> statusText = "Normal"
                        PowerManager.THERMAL_STATUS_LIGHT -> statusText = "Leve"
                        PowerManager.THERMAL_STATUS_MODERATE -> statusText = "Moderado"
                        PowerManager.THERMAL_STATUS_SEVERE -> {
                            statusText = "Severo"
                            isSevere = true
                        }
                        PowerManager.THERMAL_STATUS_CRITICAL -> {
                            statusText = "Crítico"
                            isSevere = true
                        }
                        PowerManager.THERMAL_STATUS_EMERGENCY -> {
                            statusText = "Emergência"
                            isSevere = true
                        }
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                            statusText = "Desligamento"
                            isSevere = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val summary = when {
            tempCelsius != null && statusText != null -> String.format(Locale.US, "%.1f°C (%s)", tempCelsius, statusText)
            tempCelsius != null -> String.format(Locale.US, "%.1f°C", tempCelsius)
            statusText != null -> statusText
            else -> "Não disponível no sensor"
        }

        return ThermalDiagnostic(
            batteryTempCelsius = tempCelsius,
            thermalStatusText = statusText,
            isSevereOrCritical = isSevere,
            formattedSummary = summary
        )
    }

    fun readStorage(): StorageDiagnostic? {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val used = total - stat.availableBytes
            if (total <= 0) return null
            val gb = 1024.0 * 1024.0 * 1024.0
            StorageDiagnostic(
                usedPercentage = ((used * 100) / total).toInt(),
                summary = String.format(Locale.US, "%.0f / %.0f GB", used / gb, total / gb)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun readBatteryPercent(context: Context): Int? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        } catch (_: Exception) {
            null
        }
    }

    fun readDeviceSpec(): DeviceSpec {
        return DeviceSpec(
            brand = Build.BRAND.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            apiLevel = Build.VERSION.SDK_INT,
            hardware = Build.HARDWARE
        )
    }
}
