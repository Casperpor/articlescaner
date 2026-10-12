package ru.scan.articles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val article: String, val time: Long)

/** Массив собранных артикулов. Сохраняется на телефоне, переживает закрытие приложения. */
class ArticleStore(context: Context) {
    private val prefs = context.getSharedPreferences("articles", Context.MODE_PRIVATE)
    val items: MutableList<Entry> = load()

    private fun load(): MutableList<Entry> {
        val list = ArrayList<Entry>()
        val raw = prefs.getString("items", null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Entry(o.getString("a"), o.optLong("t", 0L)))
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun save() {
        val arr = JSONArray()
        for (e in items) arr.put(JSONObject().put("a", e.article).put("t", e.time))
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(article: String) {
        items.add(Entry(article, System.currentTimeMillis()))
        save()
    }

    fun removeAt(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            save()
        }
    }

    fun clear() {
        items.clear()
        save()
    }

    fun contains(article: String) = items.any { it.article == article }

    fun learn(): Learn = Learn.from(items.map { it.article })
}
