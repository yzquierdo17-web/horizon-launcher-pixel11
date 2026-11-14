package com.slate.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher")

data class LauncherSettings(
    /** 5 columns suits the Pro XL's wide 6.8"-class display; 4–6 selectable. */
    val columns: Int = 5,
    val iconScale: Float = 1f,
    val themedIcons: Boolean = false,
    val homeLabels: Boolean = true,
    /** null = user hasn't customised yet, show Pixel-style defaults. */
    val dock: List<String>? = null,
    val home: List<String>? = null,
    val hidden: Set<String> = emptySet(),
    /** Launch counts per app key, used for drawer suggestions. */
    val usage: Map<String, Int> = emptyMap(),
    /** Separate Home row used during work hours. null = not customised. */
    val homeWork: List<String>? = null,
    val work: WorkSchedule = WorkSchedule(),
    val showMeetings: Boolean = true,
    /** Set once the user has answered (or dismissed) the "show your meetings" prompt. */
    val meetingPromptDone: Boolean = false,
    val widgets: List<WidgetSlot> = emptyList(),
)

/** Work hours. Minutes since midnight; days are ISO day numbers (1 = Monday … 7 = Sunday). */
data class WorkSchedule(
    val enabled: Boolean = false,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 18 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Pause the work profile outside work hours and turn it back on when they start. */
    val pauseAfterHours: Boolean = false,
) {
    fun isWorkTime(now: java.time.LocalDateTime): Boolean {
        if (!enabled || now.dayOfWeek.value !in days) return false
        val m = now.hour * 60 + now.minute
        return if (startMinute <= endMinute) m in startMinute until endMinute
        else m >= startMinute || m < endMinute // overnight shifts
    }
}

/** A widget placed on Home: its system id and height in rows. */
data class WidgetSlot(val id: Int, val rows: Int)

class LauncherPrefs(private val context: Context) {

    private object Keys {
        val columns = intPreferencesKey("columns")
        val iconScale = floatPreferencesKey("icon_scale")
        val themedIcons = booleanPreferencesKey("themed_icons")
        val homeLabels = booleanPreferencesKey("home_labels")
        val dock = stringPreferencesKey("dock")
        val home = stringPreferencesKey("home")
        val hidden = stringSetPreferencesKey("hidden")
        val usage = stringPreferencesKey("usage")
        val homeWork = stringPreferencesKey("home_work")
        val workEnabled = booleanPreferencesKey("work_enabled")
        val workStart = intPreferencesKey("work_start")
        val workEnd = intPreferencesKey("work_end")
        val workDays = stringSetPreferencesKey("work_days")
        val workPause = booleanPreferencesKey("work_pause_after_hours")
        val showMeetings = booleanPreferencesKey("show_meetings")
        val meetingPromptDone = booleanPreferencesKey("meeting_prompt_done")
        val widgets = stringPreferencesKey("widgets")
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { p ->
        LauncherSettings(
            columns = p[Keys.columns] ?: 5,
            iconScale = p[Keys.iconScale] ?: 1f,
            themedIcons = p[Keys.themedIcons] ?: false,
            homeLabels = p[Keys.homeLabels] ?: true,
            dock = p[Keys.dock]?.decodeList(),
            home = p[Keys.home]?.decodeList(),
            hidden = p[Keys.hidden] ?: emptySet(),
            usage = p[Keys.usage]?.decodeUsage().orEmpty(),
            homeWork = p[Keys.homeWork]?.decodeList(),
            work = WorkSchedule(
                enabled = p[Keys.workEnabled] ?: false,
                startMinute = p[Keys.workStart] ?: 9 * 60,
                endMinute = p[Keys.workEnd] ?: 18 * 60,
                days = p[Keys.workDays]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1, 2, 3, 4, 5),
                pauseAfterHours = p[Keys.workPause] ?: false,
            ),
            showMeetings = p[Keys.showMeetings] ?: true,
            meetingPromptDone = p[Keys.meetingPromptDone] ?: false,
            widgets = p[Keys.widgets]?.decodeList().orEmpty().mapNotNull { line ->
                val parts = line.split(':')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                WidgetSlot(id, parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 4) ?: 2)
            },
        )
    }

    suspend fun setColumns(value: Int) { context.dataStore.edit { it[Keys.columns] = value } }
    suspend fun setIconScale(value: Float) { context.dataStore.edit { it[Keys.iconScale] = value } }
    suspend fun setThemedIcons(value: Boolean) { context.dataStore.edit { it[Keys.themedIcons] = value } }
    suspend fun setHomeLabels(value: Boolean) { context.dataStore.edit { it[Keys.homeLabels] = value } }
    suspend fun setDock(keys: List<String>) { context.dataStore.edit { it[Keys.dock] = keys.joinToString(SEP) } }
    suspend fun setHome(keys: List<String>) { context.dataStore.edit { it[Keys.home] = keys.joinToString(SEP) } }
    suspend fun setHidden(keys: Set<String>) { context.dataStore.edit { it[Keys.hidden] = keys } }
    suspend fun setHomeWork(keys: List<String>) { context.dataStore.edit { it[Keys.homeWork] = keys.joinToString(SEP) } }
    suspend fun setShowMeetings(value: Boolean) { context.dataStore.edit { it[Keys.showMeetings] = value } }
    suspend fun setMeetingPromptDone() { context.dataStore.edit { it[Keys.meetingPromptDone] = true } }

    suspend fun setWorkSchedule(w: WorkSchedule) {
        context.dataStore.edit {
            it[Keys.workEnabled] = w.enabled
            it[Keys.workStart] = w.startMinute
            it[Keys.workEnd] = w.endMinute
            it[Keys.workDays] = w.days.map(Int::toString).toSet()
            it[Keys.workPause] = w.pauseAfterHours
        }
    }

    suspend fun setWidgets(slots: List<WidgetSlot>) {
        context.dataStore.edit { it[Keys.widgets] = slots.joinToString(SEP) { s -> "${s.id}:${s.rows}" } }
    }

    suspend fun recordLaunch(key: String) {
        context.dataStore.edit { p ->
            val counts = p[Keys.usage]?.decodeUsage().orEmpty().toMutableMap()
            counts[key] = (counts[key] ?: 0) + 1
            p[Keys.usage] = counts.entries
                .sortedByDescending { it.value }
                .take(MAX_TRACKED)
                .joinToString(SEP) { "${it.key}=${it.value}" }
        }
    }

    private companion object {
        const val MAX_TRACKED = 200
        fun String.decodeUsage(): Map<String, Int> = if (isEmpty()) emptyMap() else
            split(SEP).mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i <= 0) null else line.substring(i + 1).toIntOrNull()?.let { line.substring(0, i) to it }
            }.toMap()

        const val SEP = "\n"
        fun String.decodeList(): List<String> = if (isEmpty()) emptyList() else split(SEP)
    }
}
