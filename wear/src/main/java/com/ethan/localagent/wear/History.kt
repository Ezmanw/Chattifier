package com.ethan.localagent.wear

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One question and its answer. */
data class Exchange(val q: String, val a: String)

data class Conversation(val id: String, val updatedAt: Long, val turns: List<Exchange>) {
    val title: String get() = turns.firstOrNull()?.q.orEmpty()
}

/** Past watch conversations, kept on the watch so History works with the phone out of reach. */
class History(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<Conversation> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val turns = c.getJSONArray("turns")
            Conversation(
                c.getString("id"), c.getLong("at"),
                (0 until turns.length()).map { j -> turns.getJSONObject(j).let { Exchange(it.getString("q"), it.getString("a")) } },
            )
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Conversation>) {
        val arr = JSONArray()
        list.take(MAX_CONVERSATIONS).forEach { c ->
            val turns = JSONArray().apply { c.turns.takeLast(MAX_TURNS).forEach { put(JSONObject().put("q", it.q).put("a", it.a)) } }
            arr.put(JSONObject().put("id", c.id).put("at", c.updatedAt).put("turns", turns))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "conversations"
        const val MAX_CONVERSATIONS = 30
        const val MAX_TURNS = 20
    }
}
