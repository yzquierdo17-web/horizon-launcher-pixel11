package com.horizon.launcher

import org.json.JSONArray
import org.json.JSONObject

const val GRID_COLS = 5
const val GRID_ROWS = 5
const val MAX_DOCK = 5

const val TYPE_APP = 0
const val TYPE_FOLDER = 1
const val TYPE_WIDGET = 2

/**
 * One thing on a home screen page. Apps and folders are 1x1; widgets can span cells.
 * App references are stored as "flattenedComponent#userSerial" keys.
 */
data class HomeItem(
    val id: Long,
    val type: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val apps: List<String> = emptyList(),
    val name: String = "",
    val widgetId: Int = -1,
) {
    fun covers(cx: Int, cy: Int) = cx in x until x + spanX && cy in y until y + spanY
}

sealed interface DragPayload {
    data class FromDrawer(val key: String) : DragPayload
    data class FromHome(val itemId: Long) : DragPayload
    data class FromDock(val index: Int) : DragPayload
    data class FromFolder(val folderId: Long, val index: Int) : DragPayload
}

sealed interface DropTarget {
    data class Home(val page: Int, val x: Int, val y: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data object Remove : DropTarget
    data object None : DropTarget
}

fun encodeItems(items: List<HomeItem>): String {
    val arr = JSONArray()
    items.forEach {
        arr.put(
            JSONObject()
                .put("id", it.id)
                .put("t", it.type)
                .put("p", it.page)
                .put("x", it.x)
                .put("y", it.y)
                .put("sx", it.spanX)
                .put("sy", it.spanY)
                .put("n", it.name)
                .put("w", it.widgetId)
                .put("a", JSONArray(it.apps)),
        )
    }
    return arr.toString()
}

fun decodeItems(json: String?): List<HomeItem> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.optJSONArray("a") ?: JSONArray()
            HomeItem(
                id = o.getLong("id"),
                type = o.getInt("t"),
                page = o.getInt("p"),
                x = o.getInt("x"),
                y = o.getInt("y"),
                spanX = o.optInt("sx", 1),
                spanY = o.optInt("sy", 1),
                apps = (0 until a.length()).map { a.getString(it) },
                name = o.optString("n", ""),
                widgetId = o.optInt("w", -1),
            )
        }
    }.getOrDefault(emptyList())
}

fun encodeKeys(keys: List<String>): String = JSONArray(keys).toString()

fun decodeKeys(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}

/** Renumber pages so there are no empty pages in between. */
fun compactPages(items: List<HomeItem>): List<HomeItem> {
    val used = items.map { it.page }.distinct().sorted()
    val remap = used.withIndex().associate { (i, p) -> p to i }
    return items.map { it.copy(page = remap.getValue(it.page)) }
}

fun fits(items: List<HomeItem>, page: Int, x: Int, y: Int, sx: Int, sy: Int, excludeId: Long = -1): Boolean {
    if (x < 0 || y < 0 || x + sx > GRID_COLS || y + sy > GRID_ROWS) return false
    return items.none { other ->
        other.id != excludeId && other.page == page &&
            x < other.x + other.spanX && other.x < x + sx &&
            y < other.y + other.spanY && other.y < y + sy
    }
}

/** Closest free spot on a page for an item of the given size, or null if it's full. */
fun findFree(
    items: List<HomeItem>,
    page: Int,
    sx: Int,
    sy: Int,
    nearX: Int = 0,
    nearY: Int = 0,
    excludeId: Long = -1,
): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bestDist = Int.MAX_VALUE
    for (y in 0..GRID_ROWS - sy) for (x in 0..GRID_COLS - sx) {
        if (fits(items, page, x, y, sx, sy, excludeId)) {
            val d = (x - nearX) * (x - nearX) + (y - nearY) * (y - nearY)
            if (d < bestDist) {
                bestDist = d
                best = x to y
            }
        }
    }
    return best
}

/** First free spot reading order, starting at [startPage]; adds a new page if needed. */
fun findFreeAnywhere(items: List<HomeItem>, startPage: Int, sx: Int, sy: Int): Triple<Int, Int, Int> {
    val lastPage = items.maxOfOrNull { it.page } ?: 0
    for (page in startPage.coerceAtLeast(0)..lastPage + 1) {
        for (y in 0..GRID_ROWS - sy) for (x in 0..GRID_COLS - sx) {
            if (fits(items, page, x, y, sx, sy)) return Triple(page, x, y)
        }
    }
    return Triple(lastPage + 1, 0, 0)
}

data class LauncherSettings(
    val themedIcons: Boolean = false,
    val iconScale: Float = 1f,
    val homeLabels: Boolean = true,
    val compactClock: Boolean = false,
    val showSearch: Boolean = true,
    val wallpaperBlur: Boolean = true,
    val hidden: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("themed", themedIcons)
        .put("scale", iconScale.toDouble())
        .put("labels", homeLabels)
        .put("compact", compactClock)
        .put("search", showSearch)
        .put("blur", wallpaperBlur)
        .put("hidden", JSONArray(hidden.toList()))

    companion object {
        fun fromJson(o: JSONObject?): LauncherSettings {
            if (o == null) return LauncherSettings()
            val h = o.optJSONArray("hidden") ?: JSONArray()
            return LauncherSettings(
                themedIcons = o.optBoolean("themed", false),
                iconScale = o.optDouble("scale", 1.0).toFloat(),
                homeLabels = o.optBoolean("labels", true),
                compactClock = o.optBoolean("compact", false),
                showSearch = o.optBoolean("search", true),
                wallpaperBlur = o.optBoolean("blur", true),
                hidden = (0 until h.length()).map { h.getString(it) }.toSet(),
            )
        }
    }
}
