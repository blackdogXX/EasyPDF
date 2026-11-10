package com.blackdogxx.easypdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val page: Int,
    val pages: Int,
    val time: Long,
)

/** Список недавних файлов и запомненная страница. Хранится в SharedPreferences, без БД. */
object Recents {
    private const val PREFS = "recents"
    private const val KEY = "list"
    private const val MAX = 30

    fun load(context: Context): List<RecentFile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentFile(
                    uri = o.getString("uri"),
                    name = o.optString("name", "PDF"),
                    page = o.optInt("page", 0),
                    pages = o.optInt("pages", 0),
                    time = o.optLong("time", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<RecentFile>) {
        val array = JSONArray()
        list.take(MAX).forEach { f ->
            array.put(
                JSONObject()
                    .put("uri", f.uri)
                    .put("name", f.name)
                    .put("page", f.page)
                    .put("pages", f.pages)
                    .put("time", f.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun find(context: Context, uri: String): RecentFile? = load(context).firstOrNull { it.uri == uri }

    /** Файл открыт: поднять его наверх списка, сохранив запомненную страницу. */
    fun touch(context: Context, uri: String, name: String, pages: Int) {
        val list = load(context)
        val old = list.firstOrNull { it.uri == uri }
        val entry = RecentFile(uri, name, old?.page ?: 0, pages, System.currentTimeMillis())
        save(context, listOf(entry) + list.filter { it.uri != uri })
    }

    fun setPage(context: Context, uri: String, page: Int) {
        val list = load(context)
        if (list.none { it.uri == uri }) return
        save(context, list.map { if (it.uri == uri) it.copy(page = page) else it })
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
    }
}
