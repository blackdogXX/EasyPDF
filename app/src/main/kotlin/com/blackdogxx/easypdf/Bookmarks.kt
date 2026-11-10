package com.blackdogxx.easypdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val page: Int, val title: String)   // страница с единицы

/** Собственные закладки пользователя: для каждого файла — список страниц с подписями. */
object Bookmarks {
    private const val PREFS = "bookmarks"

    fun load(context: Context, uri: String): List<Bookmark> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uri, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Bookmark(o.getInt("p"), o.optString("t"))
            }.sortedBy { it.page }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, uri: String, list: List<Bookmark>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (list.isEmpty()) {
            prefs.remove(uri)
        } else {
            val array = JSONArray()
            list.sortedBy { it.page }.forEach { array.put(JSONObject().put("p", it.page).put("t", it.title)) }
            prefs.putString(uri, array.toString())
        }
        prefs.apply()
    }

    fun add(context: Context, uri: String, page: Int, title: String) =
        save(context, uri, load(context, uri).filter { it.page != page } + Bookmark(page, title))

    fun removePage(context: Context, uri: String, page: Int) =
        save(context, uri, load(context, uri).filter { it.page != page })

    fun removeAll(context: Context, uri: String) = save(context, uri, emptyList())
}
