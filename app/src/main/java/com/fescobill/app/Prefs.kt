package com.fescobill.app

import android.content.Context
import android.os.Build

/** Small settings kept on the phone. */
object Prefs {
    private const val FILE = "app"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The phone's browser identity, remembered so background downloads look the same as the app. */
    fun userAgent(context: Context): String = prefs(context).getString("ua", null)
        ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

    fun saveUserAgent(context: Context, ua: String) {
        if (ua.isNotBlank()) prefs(context).edit().putString("ua", ua).apply()
    }

    /** Numbers whose bill is still being fetched in the background. */
    fun isWaiting(context: Context, digits: String): Boolean =
        prefs(context).getStringSet("waiting", emptySet())!!.contains(digits)

    fun setWaiting(context: Context, digits: String, waiting: Boolean) {
        val p = prefs(context)
        val set = HashSet(p.getStringSet("waiting", emptySet())!!)
        if (waiting) set += digits else set -= digits
        p.edit().putStringSet("waiting", set).apply()
    }
}
