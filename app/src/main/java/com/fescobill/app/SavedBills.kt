package com.fescobill.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Bills the user has opened before, kept on the phone so they can be reopened with one tap. */
class SavedBills(context: Context) {

    data class Entry(val digits: String, val mode: SearchMode, val name: String, val lastOpened: Long) {
        val query: BillQuery get() = BillQuery(digits, mode)
    }

    private val prefs = context.applicationContext.getSharedPreferences("saved_bills", Context.MODE_PRIVATE)

    fun all(): List<Entry> = read().sortedByDescending { it.lastOpened }

    fun markOpened(query: BillQuery) {
        val list = read().toMutableList()
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { it.digits == query.digits }
        if (i >= 0) {
            list[i] = list[i].copy(mode = query.mode, lastOpened = now)
        } else {
            list += Entry(query.digits, query.mode, "", now)
        }
        // Keep the list short: drop the oldest unnamed entries first.
        while (list.size > MAX_ENTRIES) {
            val victim = list.filter { it.name.isBlank() }.minByOrNull { it.lastOpened }
                ?: list.minBy { it.lastOpened }
            list.remove(victim)
        }
        write(list)
    }

    fun rename(digits: String, name: String) {
        write(read().map { if (it.digits == digits) it.copy(name = name.trim()) else it })
    }

    fun delete(digits: String) {
        write(read().filterNot { it.digits == digits })
    }

    private fun read(): List<Entry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { idx ->
            val o = array.optJSONObject(idx) ?: return@mapNotNull null
            val digits = o.optString("d")
            if (RefNumber.parse(digits) == null) return@mapNotNull null
            Entry(digits, SearchMode.fromParam(o.optString("m")), o.optString("n"), o.optLong("t"))
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(list: List<Entry>) {
        val array = JSONArray()
        for (e in list) {
            array.put(JSONObject().put("d", e.digits).put("m", e.mode.param).put("n", e.name).put("t", e.lastOpened))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "bills"
        const val MAX_ENTRIES = 30
    }
}
