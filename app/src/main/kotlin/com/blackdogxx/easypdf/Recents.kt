package com.blackdogxx.easypdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val page: Int,          // с нуля
    val pages: Int,
    val time: Long,
    val pinned: Boolean = false,
    val rotation: Int = 0,  // 0, 90, 180, 270
    val zoom: Double = 1.0, // относительно масштаба «по ширине экрана»
)

/**
 * Список недавних файлов и то, как каждый был открыт в прошлый раз (страница, поворот,
 * масштаб). Хранится в SharedPreferences, без БД. Закреплённые файлы стоят первыми
 * и не вытесняются новыми.
 */
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
                    pinned = o.optBoolean("pinned", false),
                    rotation = o.optInt("rot", 0),
                    zoom = o.optDouble("zoom", 1.0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<RecentFile>) {
        val pinned = list.filter { it.pinned }.sortedByDescending { it.time }
        val others = list.filter { !it.pinned }.sortedByDescending { it.time }.take(MAX)
        val array = JSONArray()
        (pinned + others).forEach { f ->
            array.put(
                JSONObject()
                    .put("uri", f.uri)
                    .put("name", f.name)
                    .put("page", f.page)
                    .put("pages", f.pages)
                    .put("time", f.time)
                    .put("pinned", f.pinned)
                    .put("rot", f.rotation)
                    .put("zoom", f.zoom)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun find(context: Context, uri: String): RecentFile? = load(context).firstOrNull { it.uri == uri }

    private fun update(context: Context, uri: String, change: (RecentFile) -> RecentFile) {
        val list = load(context)
        if (list.none { it.uri == uri }) return
        save(context, list.map { if (it.uri == uri) change(it) else it })
    }

    /** Файл открыт: обновить время и число страниц, остальное сохранить как было. */
    fun touch(context: Context, uri: String, name: String, pages: Int) {
        val list = load(context)
        val old = list.firstOrNull { it.uri == uri }
        val entry = (old ?: RecentFile(uri, name, 0, pages, 0L))
            .copy(name = name, pages = pages, time = System.currentTimeMillis())
        save(context, listOf(entry) + list.filter { it.uri != uri })
    }

    fun setView(context: Context, uri: String, page: Int, rotation: Int, zoom: Double) =
        update(context, uri) { it.copy(page = page, rotation = rotation, zoom = zoom) }

    fun setPinned(context: Context, uri: String, pinned: Boolean) =
        update(context, uri) { it.copy(pinned = pinned) }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
    }
}
