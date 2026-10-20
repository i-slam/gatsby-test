package com.example.ramcleaner

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/** One app in the "top memory eaters" list. kb = null when the source can't measure memory. */
data class Eater(val pkg: String, val label: String, val kb: Long?, val note: String? = null)

/** Pluggable data source for per-app memory. Add a source by implementing this and listing it in [MemSources]. */
interface MemSource {
    val title: String
    fun available(ctx: Context): Boolean
    fun sample(ctx: Context): List<Eater>
}

object MemSources {
    /** Priority order: the first available source wins. */
    val all: List<MemSource> = listOf(DumpsysSource, UsageSource)
    fun best(ctx: Context): MemSource? = all.firstOrNull { it.available(ctx) }
}

/** Label for a user-installed app, or null for system apps, native processes and unknown packages. */
internal fun userAppLabel(pm: PackageManager, pkg: String): String? =
    runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        ?.takeIf { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        ?.let { pm.getApplicationLabel(it).toString() }

/**
 * Exact per-app PSS via `dumpsys meminfo -c`.
 * Needs a one-time: adb shell pm grant <pkg> android.permission.DUMP
 * Compact lines look like: proc,<oom>,<process>,<pid>,<pssKb>,...
 */
object DumpsysSource : MemSource {
    override val title = "dumpsys meminfo (exact MB)"
    private var ok = false
    private var checkedAt = 0L

    override fun available(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!ok && now - checkedAt > 30_000L) { // re-probe every 30 s until it works
            checkedAt = now
            ok = runCatching { sample(ctx).isNotEmpty() }.getOrDefault(false)
        }
        return ok
    }

    override fun sample(ctx: Context): List<Eater> {
        val proc = ProcessBuilder("dumpsys", "meminfo", "-c").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(5, TimeUnit.SECONDS)
        val pm = ctx.packageManager
        return out.lineSequence()
            .filter { it.startsWith("proc,") }
            .mapNotNull { line ->
                val f = line.split(',')
                val kb = f.getOrNull(4)?.toLongOrNull() ?: return@mapNotNull null
                f[2].substringBefore(':') to kb // merge "pkg:service" processes into their package
            }
            .groupBy({ it.first }, { it.second })
            .filterKeys { it != ctx.packageName }
            .mapNotNull { (pkg, kbs) -> userAppLabel(pm, pkg)?.let { Eater(pkg, it, kbs.sum()) } }
    }
}

/** Fallback: most recently active apps (Usage access). Cannot measure memory, so no MB values. */
object UsageSource : MemSource {
    override val title = "usage access (recently active, no MB)"

    override fun available(ctx: Context) =
        ctx.getSystemService(AppOpsManager::class.java)!!
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED

    override fun sample(ctx: Context): List<Eater> {
        val now = System.currentTimeMillis()
        val pm = ctx.packageManager
        return ctx.getSystemService(UsageStatsManager::class.java)!!
            .queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3_600_000L, now)
            .filter { it.lastTimeUsed > 0 && it.packageName != ctx.packageName }
            .groupBy { it.packageName }
            .map { (pkg, s) -> pkg to s.maxOf { it.lastTimeUsed } }
            .sortedByDescending { it.second }
            .mapNotNull { (pkg, t) -> userAppLabel(pm, pkg)?.let { Eater(pkg, it, null, "${(now - t) / 60_000}m ago") } }
    }
}
