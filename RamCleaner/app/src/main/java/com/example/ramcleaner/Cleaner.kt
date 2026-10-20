package com.example.ramcleaner

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

object Cleaner {

    data class Ram(val totalMb: Long, val availMb: Long) {
        val usedPercent: Int get() = (((totalMb - availMb) * 100) / totalMb).toInt()
    }

    data class App(val pkg: String, val label: String)

    data class Result(val freedMb: Long, val killed: Int, val skipped: Int)

    fun ram(ctx: Context): Ram {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Ram(mi.totalMem / MB, mi.availMem / MB)
    }

    /** User-installed apps, excluding this one. */
    fun userApps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
            .map { App(it.packageName, pm.getApplicationLabel(it).toString()) }
    }

    /** Kills background processes of every user app not in the whitelist. */
    fun clean(ctx: Context): Result {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val whitelist = Config(ctx).whitelist
        val (keep, kill) = userApps(ctx).partition { it.pkg in whitelist }
        val before = ram(ctx).availMb
        kill.forEach { am.killBackgroundProcesses(it.pkg) }
        Thread.sleep(800) // let the kernel reclaim
        return Result((ram(ctx).availMb - before).coerceAtLeast(0), kill.size, keep.size)
    }

    private const val MB = 1024L * 1024L
}
