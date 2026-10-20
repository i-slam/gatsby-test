package com.example.ramcleaner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    /** Declarative slider spec: add a row here to expose a new numeric setting. */
    private data class Slider(
        val min: Int, val max: Int, val step: Int,
        val get: () -> Int, val set: (Int) -> Unit,
        val label: (Int) -> String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private lateinit var ramText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var wlBtn: Button
    private lateinit var srcText: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        cfg = Config(this)

        val sliders = listOf(
            Slider(50, 95, 1, { cfg.threshold }, { cfg.threshold = it }) { "Auto-clean when RAM used ≥ $it%" },
            Slider(5, 120, 5, { cfg.intervalSec }, { cfg.intervalSec = it }) { "Check every $it s" },
            Slider(10, 600, 10, { cfg.cooldownSec }, { cfg.cooldownSec = it }) { "Wait at least $it s between cleans" },
            Slider(3, 15, 1, { cfg.topN }, { cfg.topN = it }) { "Bubble shows top $it memory eaters" },
        )

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun add(v: android.view.View) =
            root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })

        ramText = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        add(ramText); add(bar)

        sliders.forEach { sp -> sliderRow(sp).forEach(::add) }

        add(Switch(this).apply {
            text = "Auto-monitor (background)"
            isChecked = cfg.auto
            setOnCheckedChangeListener { _, on ->
                cfg.auto = on
                if (on) CleanerService.start(this@MainActivity) else CleanerService.stop(this@MainActivity)
            }
        })

        add(Switch(this).apply {
            text = "Floating bubble (graph + top memory eaters)"
            isChecked = cfg.overlay
            setOnCheckedChangeListener { sw, on ->
                if (on && !Settings.canDrawOverlays(this@MainActivity)) {
                    sw.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "Allow \"Display over other apps\", then switch on again", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                cfg.overlay = on
                if (on) OverlayService.start(this@MainActivity) else OverlayService.stop(this@MainActivity)
            }
        })

        srcText = TextView(this).apply { textSize = 13f }
        add(srcText)
        add(Button(this).apply {
            text = "Grant usage access (fallback source)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })

        wlBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, WhitelistActivity::class.java)) }
        }
        add(wlBtn)

        add(Button(this).apply {
            text = "Clean now"
            setOnClickListener {
                isEnabled = false
                thread {
                    val r = Cleaner.clean(this@MainActivity)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Freed ${r.freedMb} MB • ${r.killed} cleaned, ${r.skipped} whitelisted",
                            Toast.LENGTH_LONG
                        ).show()
                        isEnabled = true
                        refresh()
                    }
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sliderRow(sp: Slider): List<android.view.View> {
        val label = TextView(this).apply { textSize = 16f; text = sp.label(sp.get()) }
        val seek = SeekBar(this).apply {
            max = (sp.max - sp.min) / sp.step
            progress = (sp.get().coerceIn(sp.min, sp.max) - sp.min) / sp.step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = sp.min + p * sp.step
                    label.text = sp.label(v)
                    if (fromUser) sp.set(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        return listOf(label, seek)
    }

    private fun refresh() {
        val r = Cleaner.ram(this)
        ramText.text = "${r.usedPercent}% used\n${r.totalMb - r.availMb} / ${r.totalMb} MB"
        bar.progress = r.usedPercent
    }

    private val loop = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onResume() {
        super.onResume()
        wlBtn.text = "Whitelist (${cfg.whitelist.size} apps never cleaned)"
        thread {
            val src = MemSources.best(this) // may exec dumpsys: keep off the UI thread
            runOnUiThread {
                srcText.text = "Top-eaters source: ${src?.title ?: "none"}\n" +
                    "For exact MB: adb shell pm grant $packageName android.permission.DUMP"
            }
        }
        handler.post(loop)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(loop) }
}
