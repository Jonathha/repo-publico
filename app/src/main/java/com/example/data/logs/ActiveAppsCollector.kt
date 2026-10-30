package com.example.data.logs

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.data.shizuku.ShizukuManager
import com.example.data.shizuku.ShizukuStatus

enum class ActiveState { FOREGROUND, RUNNING }

enum class ActiveSource { SHIZUKU, USAGE_ACCESS, NONE }

data class ActiveApp(
    val packageName: String,
    val label: String,
    val state: ActiveState,
    /** Há quanto tempo o processo existe (ou está em primeiro plano, na fonte de uso). */
    val activeForMs: Long?,
    val memoryKb: Long?,
    val processCount: Int
)

data class ActiveAppsSnapshot(
    val apps: List<ActiveApp> = emptyList(),
    val source: ActiveSource = ActiveSource.NONE
)

data class KillResult(val stopped: Boolean, val message: String)

/**
 * Lista apps com processo vivo.
 * - Com Shizuku: processos reais (`ps`) + app em primeiro plano (`dumpsys`).
 * - Sem Shizuku, com acesso ao uso: somente apps em primeiro plano agora (eventos de uso).
 * Sem nenhum dos dois o Android não expõe processos de outros apps.
 */
class ActiveAppsCollector(
    context: Context,
    private val shizuku: ShizukuManager
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val labelCache = HashMap<String, String>()
    private var launchable: Set<String> = emptySet()
    private var launchableAt = 0L

    fun collect(): ActiveAppsSnapshot {
        val shizukuReady = shizuku.queryCurrentStatus() == ShizukuStatus.CONNECTED
        if (shizukuReady) {
            collectViaShizuku()?.let { return ActiveAppsSnapshot(it, ActiveSource.SHIZUKU) }
        }
        if (UsageAccess.isGranted(appContext)) {
            return ActiveAppsSnapshot(collectForegroundViaUsage(), ActiveSource.USAGE_ACCESS)
        }
        return ActiveAppsSnapshot(emptyList(), ActiveSource.NONE)
    }

    /** Encerra via `am force-stop` e confere no `ps` se o processo realmente sumiu. */
    fun kill(packageName: String): KillResult {
        if (!PACKAGE_REGEX.matches(packageName)) return KillResult(false, "Pacote inválido.")
        if (packageName == appContext.packageName || packageName == SHIZUKU_PKG) {
            return KillResult(false, "Este app não pode ser encerrado por aqui.")
        }
        if (shizuku.queryCurrentStatus() != ShizukuStatus.CONNECTED) {
            return KillResult(false, "Encerrar apps exige o Shizuku conectado e autorizado.")
        }
        val cmd = shizuku.execute("am force-stop $packageName")
        if (!cmd.isSuccess) {
            val reason = cmd.stderr.ifBlank { cmd.stdout }.ifBlank { "código ${cmd.exitCode}" }
            return KillResult(false, "O sistema recusou o comando: $reason")
        }
        Thread.sleep(700)
        val still = runningPackages()?.containsKey(packageName)
        return when (still) {
            false -> KillResult(true, "Aplicativo encerrado.")
            true -> KillResult(false, "O comando foi aceito, mas o app voltou a executar (serviço próprio ou reinício automático).")
            null -> KillResult(false, "Comando enviado, mas não foi possível confirmar o resultado.")
        }
    }

    // ---- Shizuku ----

    private data class Proc(var rssKb: Long, var uptimeMs: Long, var count: Int)

    private fun runningPackages(): Map<String, Proc>? {
        val res = shizuku.execute("ps -A -o PID,RSS,ETIME,NAME")
        if (res.stdout.isBlank()) return null
        val map = HashMap<String, Proc>()
        for (line in res.stdout.lineSequence()) {
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 4) continue
            if (parts[0].toLongOrNull() == null) continue // cabeçalho
            val name = parts[3]
            val pkg = name.substringBefore(':')
            if (!PACKAGE_REGEX.matches(pkg)) continue
            val rss = parts[1].toLongOrNull() ?: 0L
            val up = parseElapsed(parts[2])
            val proc = map.getOrPut(pkg) { Proc(0, 0, 0) }
            proc.rssKb += rss
            proc.count += 1
            if (up > proc.uptimeMs) proc.uptimeMs = up
        }
        return map
    }

    private fun collectViaShizuku(): List<ActiveApp>? {
        val res = shizuku.execute(
            "ps -A -o PID,RSS,ETIME,NAME; echo $TOP_MARK; " +
                "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -4"
        )
        if (res.stdout.isBlank()) return null
        val markAt = res.stdout.indexOf(TOP_MARK)
        val psPart = if (markAt >= 0) res.stdout.substring(0, markAt) else res.stdout
        val topPart = if (markAt >= 0) res.stdout.substring(markAt + TOP_MARK.length) else ""

        val top = TOP_REGEX.find(topPart)?.groupValues?.get(1)
        val procs = HashMap<String, Proc>()
        for (line in psPart.lineSequence()) {
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 4 || parts[0].toLongOrNull() == null) continue
            val pkg = parts[3].substringBefore(':')
            if (!PACKAGE_REGEX.matches(pkg)) continue
            val proc = procs.getOrPut(pkg) { Proc(0, 0, 0) }
            proc.rssKb += parts[1].toLongOrNull() ?: 0L
            proc.count += 1
            val up = parseElapsed(parts[2])
            if (up > proc.uptimeMs) proc.uptimeMs = up
        }

        val visible = launchablePackages()
        val self = appContext.packageName
        return procs.asSequence()
            .filter { it.key in visible && it.key != self }
            .map { (pkg, p) ->
                ActiveApp(
                    packageName = pkg,
                    label = labelOf(pkg),
                    state = if (pkg == top) ActiveState.FOREGROUND else ActiveState.RUNNING,
                    activeForMs = p.uptimeMs.takeIf { it > 0 },
                    memoryKb = p.rssKb.takeIf { it > 0 },
                    processCount = p.count
                )
            }
            .sortedWith(
                compareBy<ActiveApp> { it.state != ActiveState.FOREGROUND }
                    .thenByDescending { it.memoryKb ?: 0L }
            )
            .toList()
    }

    // ---- Eventos de uso (sem Shizuku) ----

    @Suppress("DEPRECATION")
    private fun collectForegroundViaUsage(): List<ActiveApp> {
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - FOREGROUND_WINDOW_MS, now)
        val event = UsageEvents.Event()
        // pacote/activity -> instante em que foi retomada e ainda não pausada.
        val resumed = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val key = event.packageName + "/" + (event.className ?: "")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> resumed[key] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND, EVENT_ACTIVITY_STOPPED -> resumed.remove(key)
            }
        }
        val self = appContext.packageName
        val visible = launchablePackages()
        return resumed.entries
            .groupBy({ it.key.substringBefore('/') }, { it.value })
            .filter { it.key in visible && it.key != self }
            .map { (pkg, times) ->
                ActiveApp(
                    packageName = pkg,
                    label = labelOf(pkg),
                    state = ActiveState.FOREGROUND,
                    activeForMs = (now - times.min()).coerceAtLeast(0),
                    memoryKb = null,
                    processCount = 0
                )
            }
    }

    // ---- util ----

    private fun launchablePackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (launchable.isEmpty() || now - launchableAt > 60_000) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            launchable = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
            launchableAt = now
        }
        return launchable
    }

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /** ETIME do ps: [[DD-]HH:]MM:SS. */
    private fun parseElapsed(raw: String): Long {
        var days = 0L
        var rest = raw
        if (rest.contains('-')) {
            days = rest.substringBefore('-').toLongOrNull() ?: 0L
            rest = rest.substringAfter('-')
        }
        val nums = rest.split(':').map { it.toLongOrNull() ?: 0L }
        val secs = when (nums.size) {
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            2 -> nums[0] * 60 + nums[1]
            else -> nums.firstOrNull() ?: 0L
        }
        return (days * 86400 + secs) * 1000
    }

    companion object {
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val TOP_MARK = "__TOP__"
        private const val FOREGROUND_WINDOW_MS = 2 * 60 * 60 * 1000L
        private const val EVENT_ACTIVITY_STOPPED = 23 // UsageEvents.Event.ACTIVITY_STOPPED (API 29)
        private val WHITESPACE = Regex("\\s+")
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val TOP_REGEX = Regex("\\bu\\d+\\s+([A-Za-z0-9_.]+)/")
    }
}
