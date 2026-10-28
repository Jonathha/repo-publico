package com.example.data.freefire

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class FreeFirePackage(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isMax: Boolean,
    val isInstalled: Boolean
)

object FreeFireDetector {
    const val PKG_FREE_FIRE_STANDARD = "com.dts.freefireth"
    const val PKG_FREE_FIRE_MAX = "com.dts.freefiremax"

    fun detectInstalledPackages(context: Context): List<FreeFirePackage> {
        val pm = context.packageManager
        val candidates = listOf(
            PKG_FREE_FIRE_STANDARD to "Free Fire",
            PKG_FREE_FIRE_MAX to "Free Fire MAX"
        )

        return candidates.map { (pkg, label) ->
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val appLabel = pm.getApplicationLabel(info.applicationInfo ?: pm.getApplicationInfo(pkg, 0)).toString()
                @Suppress("DEPRECATION")
                val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
                FreeFirePackage(
                    packageName = pkg,
                    appName = if (appLabel.isNotBlank()) appLabel else label,
                    versionName = info.versionName ?: "Desconhecida",
                    versionCode = vCode,
                    isMax = pkg == PKG_FREE_FIRE_MAX,
                    isInstalled = true
                )
            } catch (_: PackageManager.NameNotFoundException) {
                FreeFirePackage(
                    packageName = pkg,
                    appName = label,
                    versionName = "",
                    versionCode = 0,
                    isMax = pkg == PKG_FREE_FIRE_MAX,
                    isInstalled = false
                )
            } catch (_: Exception) {
                FreeFirePackage(
                    packageName = pkg,
                    appName = label,
                    versionName = "",
                    versionCode = 0,
                    isMax = pkg == PKG_FREE_FIRE_MAX,
                    isInstalled = false
                )
            }
        }
    }

    fun launchGame(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
