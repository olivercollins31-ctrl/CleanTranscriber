package com.olly.cleantranscriber

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("ct", Context.MODE_PRIVATE)

    var fillers: Boolean
        get() = p.getBoolean("fillers", true)
        set(v) { p.edit().putBoolean("fillers", v).apply() }

    var punctuation: Boolean
        get() = p.getBoolean("punctuation", true)
        set(v) { p.edit().putBoolean("punctuation", v).apply() }

    var lang: String
        get() = p.getString("lang", "en-GB") ?: "en-GB"
        set(v) { p.edit().putString("lang", v).apply() }
}
