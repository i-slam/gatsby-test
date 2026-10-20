package com.example.ramcleaner

import android.content.Context

/** Single source of truth for all user settings (SharedPreferences-backed). */
class Config(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var threshold: Int
        get() = p.getInt("threshold", 80)
        set(v) = p.edit().putInt("threshold", v).apply()

    var intervalSec: Int
        get() = p.getInt("interval", 10)
        set(v) = p.edit().putInt("interval", v).apply()

    var cooldownSec: Int
        get() = p.getInt("cooldown", 60)
        set(v) = p.edit().putInt("cooldown", v).apply()

    var auto: Boolean
        get() = p.getBoolean("auto", false)
        set(v) = p.edit().putBoolean("auto", v).apply()

    var overlay: Boolean
        get() = p.getBoolean("overlay", false)
        set(v) = p.edit().putBoolean("overlay", v).apply()

    var topN: Int
        get() = p.getInt("topN", 5)
        set(v) = p.edit().putInt("topN", v).apply()

    /** Key into OverlayService.sorts ("size" | "growth"). */
    var sortMode: String
        get() = p.getString("sort", "size")!!
        set(v) = p.edit().putString("sort", v).apply()

    /** Package names that are never killed. */
    var whitelist: Set<String>
        get() = p.getStringSet("whitelist", emptySet())!!.toSet() // copy: never mutate the returned set
        set(v) = p.edit().putStringSet("whitelist", v.toSet()).apply()

    fun setWhitelisted(pkg: String, on: Boolean) {
        whitelist = if (on) whitelist + pkg else whitelist - pkg
    }
}
