package com.example.data.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isSuccess: Boolean
)

object ShizukuProcessHelper {
    private const val TAG = "ShizukuProcessHelper"

    fun executeShell(command: String): CommandResult {
        return try {
            if (!Shizuku.pingBinder()) {
                return CommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Serviço Shizuku não está em execução ou não respondeu ao ping.",
                    isSuccess = false
                )
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return CommandResult(
                    exitCode = -2,
                    stdout = "",
                    stderr = "Permissão do Shizuku não foi concedida pelo usuário.",
                    isSuccess = false
                )
            }

            val cmdArray = arrayOf("sh", "-c", command)
            val shizukuClass = Shizuku::class.java
            val method = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val remoteProcess = method.invoke(null, cmdArray, null, null)
                ?: return CommandResult(
                    exitCode = -3,
                    stdout = "",
                    stderr = "Falha ao instanciar processo remoto Shizuku.",
                    isSuccess = false
                )

            val inputStream = remoteProcess.javaClass.getMethod("getInputStream").invoke(remoteProcess) as InputStream
            val errorStream = remoteProcess.javaClass.getMethod("getErrorStream").invoke(remoteProcess) as InputStream

            val stdoutText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }.trim()
            val stderrText = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }.trim()

            val waitForMethod = remoteProcess.javaClass.getMethod("waitFor")
            val exitCode = waitForMethod.invoke(remoteProcess) as? Int ?: 0

            val destroyMethod = remoteProcess.javaClass.getMethod("destroy")
            destroyMethod.invoke(remoteProcess)

            val hasExplicitError = stderrText.contains("Error:", ignoreCase = true) ||
                    stderrText.contains("Exception", ignoreCase = true) ||
                    stderrText.contains("Unknown package", ignoreCase = true) ||
                    stderrText.contains("Failure", ignoreCase = true)
            val isSuccess = (exitCode == 0 && !hasExplicitError) || stdoutText.contains("Success", ignoreCase = true)

            CommandResult(
                exitCode = exitCode,
                stdout = stdoutText,
                stderr = stderrText,
                isSuccess = isSuccess
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar comando via Shizuku: $command", e)
            CommandResult(
                exitCode = -99,
                stdout = "",
                stderr = "Exceção ao invocar Shizuku: ${e.message ?: e.javaClass.simpleName}",
                isSuccess = false
            )
        }
    }
}
