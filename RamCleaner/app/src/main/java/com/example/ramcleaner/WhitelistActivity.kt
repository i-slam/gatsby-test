package com.example.ramcleaner

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.concurrent.thread

/** Searchable checklist of user apps; checked = never killed. Saves on every tap. */
class WhitelistActivity : Activity() {

    private lateinit var cfg: Config
    private var all: List<Cleaner.App> = emptyList()
    private lateinit var adapter: ArrayAdapter<Cleaner.App>
    private lateinit var count: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Config(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        count = TextView(this).apply { textSize = 18f }
        val search = EditText(this).apply { hint = "Search apps"; isSingleLine = true }
        val clear = Button(this).apply {
            text = "Clear whitelist"
            setOnClickListener { cfg.whitelist = emptySet(); adapter.notifyDataSetChanged(); updateCount() }
        }

        adapter = object : ArrayAdapter<Cleaner.App>(this, android.R.layout.simple_list_item_multiple_choice) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent) as CheckedTextView
                val app = getItem(pos)!!
                v.text = "${app.label}\n${app.pkg}"
                v.isChecked = app.pkg in cfg.whitelist
                return v
            }
        }

        val list = ListView(this).apply {
            this.adapter = this@WhitelistActivity.adapter
            setOnItemClickListener { _, v, pos, _ ->
                val app = this@WhitelistActivity.adapter.getItem(pos)!!
                val on = app.pkg !in cfg.whitelist
                cfg.setWhitelisted(app.pkg, on)
                (v as CheckedTextView).isChecked = on
                updateCount()
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = show(e.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, 0)
            addView(count)
            addView(search, LinearLayout.LayoutParams(-1, -2))
            addView(clear, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        updateCount()
        thread {
            val wl = cfg.whitelist
            val apps = Cleaner.userApps(this)
                .sortedWith(compareByDescending<Cleaner.App> { it.pkg in wl }.thenBy { it.label.lowercase() })
            runOnUiThread { all = apps; show(search.text.toString()) }
        }
    }

    private fun show(q: String) {
        val needle = q.trim().lowercase()
        adapter.clear()
        adapter.addAll(if (needle.isEmpty()) all else all.filter {
            needle in it.label.lowercase() || needle in it.pkg.lowercase()
        })
    }

    private fun updateCount() {
        count.text = "Whitelisted: ${cfg.whitelist.size} — checked apps are never cleaned"
    }
}
