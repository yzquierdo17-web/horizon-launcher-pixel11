package com.slate.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slate.launcher.data.AppEntry
import com.slate.launcher.data.AppRepository
import com.slate.launcher.data.CalendarRepository
import com.slate.launcher.data.LauncherPrefs
import com.slate.launcher.data.LauncherSettings
import com.slate.launcher.data.Meeting
import com.slate.launcher.data.WidgetRepository
import com.slate.launcher.data.WidgetSlot
import com.slate.launcher.data.WorkSchedule
import android.appwidget.AppWidgetProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class HomeWidget(val id: Int, val rows: Int, val info: AppWidgetProviderInfo)

data class LauncherUiState(
    val loading: Boolean = true,
    val drawerApps: List<AppEntry> = emptyList(),
    val suggestedApps: List<AppEntry> = emptyList(),
    val homeApps: List<AppEntry> = emptyList(),
    val dockApps: List<AppEntry> = emptyList(),
    val hiddenApps: List<AppEntry> = emptyList(),
    val widgets: List<HomeWidget> = emptyList(),
    val hasWorkProfile: Boolean = false,
    val workPaused: Boolean = false,
    /** Inside the user's work hours right now. */
    val workTime: Boolean = false,
    val settings: LauncherSettings = LauncherSettings(),
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    val repository = AppRepository(application, viewModelScope)
    val calendar = CalendarRepository(application)
    val widgetRepo = WidgetRepository(application)
    private val prefs = LauncherPrefs(application)

    /** Ticks every 30 s so work hours and the meeting line stay current. */
    private val clock = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDateTime.now())

    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<LauncherUiState> =
        combine(repository.apps, prefs.settings, repository.workProfile, clock) { apps, s, work, now ->
            if (apps == null) return@combine LauncherUiState(settings = s)
            val byKey = apps.associateBy { it.key }
            val byPackage = apps.filter { !it.isWorkProfile }.associateBy { it.packageName }
            val workTime = s.work.isWorkTime(now)
            fun resolve(saved: List<String>?, defaults: List<String>) =
                saved?.mapNotNull { byKey[it] } ?: defaults.mapNotNull { byPackage[it] }
            val homeApps = if (workTime) {
                // Work hours: your work Home row, defaulting to the first work-profile apps.
                s.homeWork?.mapNotNull { byKey[it] } ?: apps.filter { it.isWorkProfile }.take(s.columns)
                    .ifEmpty { resolve(s.home, DEFAULT_HOME) }
            } else resolve(s.home, DEFAULT_HOME)
            LauncherUiState(
                loading = false,
                drawerApps = apps.filter { it.key !in s.hidden },
                suggestedApps = s.usage.entries
                    .sortedByDescending { it.value }
                    .mapNotNull { byKey[it.key] }
                    .filter { it.key !in s.hidden && !it.isPaused }
                    .take(s.columns),
                homeApps = homeApps,
                dockApps = resolve(s.dock, DEFAULT_DOCK).take(MAX_DOCK),
                hiddenApps = apps.filter { it.key in s.hidden },
                widgets = s.widgets.mapNotNull { slot -> widgetRepo.info(slot.id)?.let { HomeWidget(slot.id, slot.rows, it) } },
                hasWorkProfile = work != null,
                workPaused = work?.paused == true,
                workTime = workTime,
                settings = s,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherUiState())

    /** Current or next meeting; null when none, calendar access is off, or the user hid it. */
    val meeting: StateFlow<Meeting?> =
        combine(clock, calendar.changes(), refresh, prefs.settings.map { it.showMeetings }.distinctUntilChanged()) { now, _, _, show ->
            if (show) calendar.nextMeeting() else null
        }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homePressed: SharedFlow<Unit> = _homePressed.asSharedFlow()

    /** Last work-hours state we acted on; null until first seen (we don't act on launch). */
    private var lastWorkTime: Boolean? = null

    init {
        repository.start()
        // Auto pause / resume work apps at the edges of work hours.
        viewModelScope.launch {
            uiState.map { Triple(it.loading, it.workTime, it.settings.work) }.distinctUntilChanged().collect { (loading, workTime, schedule) ->
                if (loading) return@collect
                applyWorkHours(workTime, schedule)
            }
        }
    }

    private fun applyWorkHours(workTime: Boolean, schedule: WorkSchedule) {
        val previous = lastWorkTime
        lastWorkTime = workTime
        if (previous == null || previous == workTime) return
        if (!schedule.enabled || !schedule.pauseAfterHours || !uiState.value.hasWorkProfile) return
        val ok = repository.setWorkPaused(paused = !workTime)
        if (!ok) lastWorkTime = previous // try again on the next resume
    }

    /** Called when Home comes to the front: the moment Android lets us pause/resume work apps. */
    fun onResume() {
        val s = uiState.value
        if (!s.loading) {
            val prev = lastWorkTime
            if (prev != null && prev != s.workTime) applyWorkHours(s.workTime, s.settings.work)
        }
        refresh.value++
    }

    override fun onCleared() = repository.stop()

    fun onHomePressed() { _homePressed.tryEmit(Unit) }

    fun recordLaunch(app: AppEntry) = viewModelScope.launch { prefs.recordLaunch(app.key) }

    // ---- Work profile ----

    fun setWorkPaused(paused: Boolean) { repository.setWorkPaused(paused) }

    fun setWorkSchedule(w: WorkSchedule) = viewModelScope.launch { prefs.setWorkSchedule(w) }

    // ---- Home & dock editing (Home row follows work hours) ----

    private val homeKeys get() = uiState.value.homeApps.map { it.key }
    private val dockKeys get() = uiState.value.dockApps.map { it.key }

    private suspend fun saveHome(keys: List<String>) =
        if (uiState.value.workTime) prefs.setHomeWork(keys) else prefs.setHome(keys)

    fun addToHome(app: AppEntry) = viewModelScope.launch {
        if (app.key !in homeKeys) saveHome(homeKeys + app.key)
    }

    fun removeFromHome(app: AppEntry) = viewModelScope.launch { saveHome(homeKeys - app.key) }

    fun addToDock(app: AppEntry) = viewModelScope.launch {
        val current = dockKeys
        when {
            app.key in current -> Unit
            current.size >= MAX_DOCK -> repository.toast("Dock is full")
            else -> prefs.setDock(current + app.key)
        }
    }

    fun removeFromDock(app: AppEntry) = viewModelScope.launch { prefs.setDock(dockKeys - app.key) }

    fun hide(app: AppEntry) = viewModelScope.launch {
        prefs.setHidden(uiState.value.settings.hidden + app.key)
    }

    fun unhide(app: AppEntry) = viewModelScope.launch {
        prefs.setHidden(uiState.value.settings.hidden - app.key)
    }

    // ---- Widgets ----

    fun addWidget(id: Int) = viewModelScope.launch {
        val slots = uiState.value.settings.widgets
        if (slots.none { it.id == id }) prefs.setWidgets(slots + WidgetSlot(id, widgetRepo.defaultRows(id)))
    }

    fun cancelWidget(id: Int) = widgetRepo.delete(id)

    fun removeWidget(id: Int) = viewModelScope.launch {
        widgetRepo.delete(id)
        prefs.setWidgets(uiState.value.settings.widgets.filterNot { it.id == id })
    }

    fun resizeWidget(id: Int, rows: Int) = viewModelScope.launch {
        prefs.setWidgets(uiState.value.settings.widgets.map { if (it.id == id) it.copy(rows = rows.coerceIn(1, 4)) else it })
    }

    fun moveWidget(id: Int, up: Boolean) = viewModelScope.launch {
        val list = uiState.value.settings.widgets.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return@launch
        list[i] = list[j].also { list[j] = list[i] }
        prefs.setWidgets(list)
    }

    /** Result of a widget's own setup screen (delivered via MainActivity.onActivityResult). */
    fun onWidgetConfigured(success: Boolean) {
        val id = widgetRepo.consumePendingConfigure()
        if (id == WidgetRepository.INVALID) return
        if (success) addWidget(id) else widgetRepo.delete(id)
    }

    // ---- Settings ----

    fun setColumns(v: Int) = viewModelScope.launch { prefs.setColumns(v.coerceIn(4, 6)) }
    fun setIconScale(v: Float) = viewModelScope.launch { prefs.setIconScale(v.coerceIn(0.8f, 1.25f)) }
    fun setThemedIcons(v: Boolean) = viewModelScope.launch { prefs.setThemedIcons(v) }
    fun setHomeLabels(v: Boolean) = viewModelScope.launch { prefs.setHomeLabels(v) }
    fun setShowMeetings(v: Boolean) = viewModelScope.launch {
        prefs.setShowMeetings(v)
        prefs.setMeetingPromptDone()
        refresh.value++
    }
    fun dismissMeetingPrompt() = viewModelScope.launch { prefs.setMeetingPromptDone() }
    fun refreshMeetings() { refresh.value++ }

    companion object {
        const val MAX_DOCK = 5

        /** Pixel-style defaults; anything not installed is skipped. */
        val DEFAULT_DOCK = listOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.vending",
            "com.android.chrome",
            "com.google.android.GoogleCamera",
        )
        val DEFAULT_HOME = listOf(
            "com.google.android.gm",
            "com.google.android.apps.photos",
            "com.google.android.youtube",
            "com.google.android.apps.maps",
        )
    }
}
