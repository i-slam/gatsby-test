package com.example.ramcleaner

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Draggable floating bubble showing RAM %. Tap to open a panel with:
 * live RAM graph, top memory eaters (size or growth), tap row = clean app,
 * long-press row = toggle whitelist, and a Clean-all button.
 */
class OverlayService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<Int>()
    private val trend = TrendTracker()

    /** Bubble colour bands relative to the threshold. First match wins. */
    private val levels: List<Pair<(Int, Int) -> Boolean, Int>> = listOf(
        { used: Int, th: Int -> used >= th } to 0xFFC62828.toInt(),
        { used: Int, th: Int -> used >= th - 15 } to 0xFFF9A825.toInt(),
        { _: Int, _: Int -> true } to 0xFF2E7D32.toInt(),
    )

    /** Sort modes: name -> descending sort key. Add an entry to add a mode. */
    private val sorts: Map<String, (Eater) -> Long> = linkedMapOf(
        "size" to { e -> e.kb ?: 0L },
        "growth" to { e -> trend.deltaKb(e.pkg) ?: Long.MIN_VALUE },
    )

    private lateinit var wm: WindowManager
    private lateinit var cfg: Config
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var spark: SparkView
    private lateinit var ramLine: TextView
    private lateinit var srcLine: TextView
    private lateinit var sortBtn: TextView
    private lateinit var rows: LinearLayout

    private var eaters: List<Eater> = emptyList()
    private var expanded = false
    private var sampling = false
    private var ticks = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        wm = getSystemService(WindowManager::class.java)!!
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
            NotificationChannel(CH, "Floating RAM monitor", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            2, Notification.Builder(this, CH)
                .setContentTitle("RAM bubble active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        )
        buildBubble()
        buildPanel()
        h.post(tick)
    }

    // ---------- loop ----------

    private val tick = object : Runnable {
        override fun run() {
            val r = Cleaner.ram(this@OverlayService)
            history.addLast(r.usedPercent)
            while (history.size > HISTORY) history.removeFirst()

            bubble.text = "${r.usedPercent}%"
            (bubble.background as GradientDrawable)
                .setColor(levels.first { it.first(r.usedPercent, cfg.threshold) }.second)

            if (expanded) {
                ramLine.text = "${r.usedPercent}%  •  ${r.totalMb - r.availMb}/${r.totalMb} MB"
                spark.set(history.toList(), cfg.threshold, HISTORY)
                if (ticks++ % EATER_EVERY == 0) sampleEaters()
            }
            h.postDelayed(this, TICK_MS)
        }
    }

    private fun sampleEaters() {
        if (sampling) return
        sampling = true
        thread {
            val src = MemSources.best(this)
            val list = runCatching { src?.sample(this) }.getOrNull().orEmpty()
            h.post {
                sampling = false
                trend.record(list)
                eaters = list
                srcLine.text = "TOP ${cfg.topN} • " + (src?.title ?: "no data source — set one up in the app")
                renderRows()
            }
        }
    }

    // ---------- views ----------

    private fun overlayParams(w: Int, hgt: Int) = WindowManager.LayoutParams(
        w, hgt,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            elevation = dp(6).toFloat()
        }
        bubbleLp = overlayParams(dp(52), dp(52)).apply { x = dp(8); y = dp(200) }
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = bubbleLp.x; sy = bubbleLp.y; tx = e.rawX; ty = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (abs(dx) + abs(dy) > dp(8)) moved = true
                        if (moved) {
                            bubbleLp.x = sx + dx.toInt()
                            bubbleLp.y = sy + dy.toInt()
                            wm.updateViewLayout(bubble, bubbleLp)
                            if (expanded) placePanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) toggle()
                }
                return true
            }
        })
        wm.addView(bubble, bubbleLp)
    }

    private fun buildPanel() {
        fun label(size: Float, alpha: Int = 255) = TextView(this).apply {
            textSize = size; setTextColor(Color.argb(alpha, 255, 255, 255))
        }
        ramLine = label(15f).apply { typeface = Typeface.DEFAULT_BOLD }
        sortBtn = label(13f).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { cycleSort() }
        }
        val close = label(18f).apply {
            text = "✕"; setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { toggle() }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ramLine, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sortBtn)
            addView(close)
        }
        spark = SparkView(this)
        srcLine = label(11f, 150)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val clean = Button(this).apply {
            text = "Clean now"
            setOnClickListener { cleanAll(this) }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF01E1E24.toInt())
            }
            elevation = dp(8).toFloat()
            addView(header)
            addView(spark, LinearLayout.LayoutParams(-1, dp(90)).apply { topMargin = dp(6) })
            addView(srcLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(rows)
            addView(clean, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        panelLp = overlayParams(dp(PANEL_W), WindowManager.LayoutParams.WRAP_CONTENT)
        updateSortLabel()
    }

    private fun placePanel() {
        val screenW = resources.displayMetrics.widthPixels
        panelLp.x = bubbleLp.x.coerceIn(0, (screenW - dp(PANEL_W)).coerceAtLeast(0))
        panelLp.y = bubbleLp.y + dp(58)
        if (panel.parent != null) wm.updateViewLayout(panel, panelLp)
    }

    private fun toggle() {
        expanded = !expanded
        if (expanded) {
            placePanel()
            wm.addView(panel, panelLp)
            ticks = 0
            h.removeCallbacks(tick)
            h.post(tick) // fill graph + sample immediately
        } else if (panel.parent != null) {
            wm.removeView(panel)
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        val wl = cfg.whitelist
        val key = sorts[cfg.sortMode] ?: sorts.values.first()
        eaters.sortedByDescending(key).take(cfg.topN).forEach { rows.addView(row(it, it.pkg in wl)) }
        if (eaters.isEmpty()) rows.addView(TextView(this).apply { text = "No data yet"; setTextColor(Color.GRAY) })
    }

    private fun row(e: Eater, locked: Boolean): View {
        val d = trend.deltaKb(e.pkg) ?: 0L
        val name = TextView(this).apply {
            text = (if (locked) "🔒 " else "") + e.label
            setTextColor(Color.WHITE); textSize = 14f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val value = TextView(this).apply {
            textSize = 13f
            text = e.kb?.let { "${it / 1024} MB" + trendText(d) } ?: e.note.orEmpty()
            setTextColor(
                when {
                    d >= GROW_KB -> 0xFFFF8A65.toInt()
                    d <= -GROW_KB -> 0xFF81C784.toInt()
                    else -> 0xFFBDBDBD.toInt()
                }
            )
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
            setOnClickListener { kill(e, locked) }
            setOnLongClickListener {
                cfg.setWhitelisted(e.pkg, !locked)
                toast(if (locked) "${e.label} removed from whitelist" else "${e.label} whitelisted")
                renderRows()
                true
            }
        }
    }

    private fun trendText(d: Long) = when {
        d >= GROW_KB -> "  ▲${d / 1024}"
        d <= -GROW_KB -> "  ▼${-d / 1024}"
        else -> ""
    }

    // ---------- actions ----------

    private fun kill(e: Eater, locked: Boolean) {
        if (locked) { toast("${e.label} is whitelisted (long-press to change)"); return }
        getSystemService(ActivityManager::class.java)!!.killBackgroundProcesses(e.pkg)
        toast("Cleaned ${e.label}")
        h.postDelayed({ sampleEaters() }, 800)
    }

    private fun cleanAll(btn: Button) {
        btn.isEnabled = false
        thread {
            val r = Cleaner.clean(this)
            h.post {
                btn.isEnabled = true
                toast("Freed ${r.freedMb} MB • ${r.killed} cleaned, ${r.skipped} whitelisted")
                sampleEaters()
            }
        }
    }

    private fun cycleSort() {
        val keys = sorts.keys.toList()
        cfg.sortMode = keys[(keys.indexOf(cfg.sortMode) + 1) % keys.size]
        updateSortLabel()
        renderRows()
    }

    private fun updateSortLabel() { sortBtn.text = "⇅ ${cfg.sortMode}" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (panel.parent != null) wm.removeView(panel)
        if (bubble.parent != null) wm.removeView(bubble)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        private const val CH = "overlay"
        private const val TICK_MS = 2000L
        private const val HISTORY = 90      // 3 min of graph at 2 s
        private const val EATER_EVERY = 3   // refresh eaters every 6 s while open
        private const val GROW_KB = 5 * 1024L // ignore jitter under 5 MB
        private const val PANEL_W = 300

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}
