package com.example.ramcleaner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.concurrent.thread

class CleanerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastClean = 0L

    private val tick = object : Runnable {
        override fun run() {
            val cfg = Config(this@CleanerService) // re-read each tick so setting changes apply live
            val ram = Cleaner.ram(this@CleanerService)
            val now = System.currentTimeMillis()
            if (ram.usedPercent >= cfg.threshold && now - lastClean > cfg.cooldownSec * 1000L) {
                lastClean = now
                thread {
                    val r = Cleaner.clean(this@CleanerService)
                    notify("Freed ${r.freedMb} MB • ${r.killed} apps cleaned, ${r.skipped} whitelisted")
                }
            } else {
                notify("RAM ${ram.usedPercent}% • threshold ${cfg.threshold}%")
            }
            handler.postDelayed(this, cfg.intervalSec * 1000L)
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CH, "RAM monitor", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build("Monitoring…"))
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun build(text: String) = Notification.Builder(this, CH)
        .setContentTitle("RAM Cleaner")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOnlyAlertOnce(true)
        .build()

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java)!!.notify(1, build(text))

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        private const val CH = "ram"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CleanerService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CleanerService::class.java))
    }
}
