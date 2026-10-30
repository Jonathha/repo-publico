package com.example.data.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class ShizukuManager(private val context: Context) {

    private val _status = MutableStateFlow(ShizukuStatus.DISCONNECTED)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateStatus()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            _status.value = ShizukuStatus.CONNECTED
        } else {
            _status.value = ShizukuStatus.NO_PERMISSION
        }
    }

    fun initialize() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {
            // Se Shizuku não estiver presente no dispositivo
        }
        updateStatus()
    }

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {
        }
    }

    fun updateStatus() {
        _status.value = queryCurrentStatus()
    }

    fun queryCurrentStatus(): ShizukuStatus {
        return try {
            val isInstalled = isShizukuInstalled()
            if (!isInstalled && !Shizuku.pingBinder()) {
                return ShizukuStatus.UNAVAILABLE
            }

            if (!Shizuku.pingBinder()) {
                return ShizukuStatus.DISCONNECTED
            }

            if (Shizuku.isPreV11()) {
                return ShizukuStatus.CONNECTED
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.CONNECTED
            } else {
                ShizukuStatus.NO_PERMISSION
            }
        } catch (_: Exception) {
            ShizukuStatus.DISCONNECTED
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun execute(
        command: String,
        timeoutMs: Long = ShizukuProcessHelper.DEFAULT_TIMEOUT_MS
    ): CommandResult {
        return ShizukuProcessHelper.executeShell(command, timeoutMs)
    }
}
