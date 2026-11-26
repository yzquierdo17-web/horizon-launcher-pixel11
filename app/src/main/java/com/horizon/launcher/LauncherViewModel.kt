package com.horizon.launcher

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Locale
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.math.ceil

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val serial: Long,
    val icon: ImageBitmap,
    val isWork: Boolean = false,
    /** Work profile is paused: shown greyed out; tapping offers to turn work apps on. */
    val isPaused: Boolean = false,
    /** False when you chose to hide this app's name under its icon. */
    val showLabel: Boolean = true,
    /** Drawer group: AppGroup id. */
    val group: String = "other",
    /** The app's own name, before any rename. */
    val originalLabel: String = label,
) {
    val packageName: String get() = component.packageName
    val key: String get() = component.flattenToString() + "#" + serial
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)
    private val prefs = app.getSharedPreferences("horizon", Context.MODE_PRIVATE)

    val widgetHost = AppWidgetHost(app, WIDGET_HOST_ID)
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(app)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _appIndex = MutableStateFlow<Map<String, AppEntry>>(emptyMap())
    val appIndex: StateFlow<Map<String, AppEntry>> = _appIndex.asStateFlow()

    private val _items = MutableStateFlow(decodeItems(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<HomeItem>> = _items.asStateFlow()

    private val _dock = MutableStateFlow(decodeKeys(prefs.getString(KEY_DOCK, null)))
    val dock: StateFlow<List<String>> = _dock.asStateFlow()

    private val _settings = MutableStateFlow(
        LauncherSettings.fromJson(runCatching { JSONObject(prefs.getString(KEY_SETTINGS, "{}") ?: "{}") }.getOrNull()),
    )
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    private val _searchFocus = MutableStateFlow(false)
    val searchFocus: StateFlow<Boolean> = _searchFocus.asStateFlow()

    private val _openFolder = MutableStateFlow<Long?>(null)
    val openFolder: StateFlow<Long?> = _openFolder.asStateFlow()

    private val _widgetPicker = MutableStateFlow(false)
    val widgetPicker: StateFlow<Boolean> = _widgetPicker.asStateFlow()

    /** Bumped when Home is pressed while already on the home screen. */
    private val _homeSignal = MutableStateFlow(0)
    val homeSignal: StateFlow<Int> = _homeSignal.asStateFlow()

    // ---------- Work profile, work hours, meetings, suggestions ----------

    private val _workProfile = MutableStateFlow<WorkProfile?>(null)
    val workProfile: StateFlow<WorkProfile?> = _workProfile.asStateFlow()

    /** Ticks every 30 s so work hours and the meeting line stay current. */
    private val now = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDateTime.now())

    val workTime: StateFlow<Boolean> = combine(settings, now) { s, t -> s.isWorkTime(t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val calendar = CalendarRepository(app)
    private val meetingRefresh = MutableStateFlow(0)
    val meeting: StateFlow<Meeting?> =
        combine(now, calendar.changes(), meetingRefresh, settings.map { it.showMeetings }.distinctUntilChanged()) { _, _, _, show ->
            if (show) runCatching { calendar.nextMeeting() }.getOrNull() else null
        }.flowOn(Dispatchers.IO)
            .catch { emit(null) } // a calendar problem must never take the launcher down
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _usage = MutableStateFlow(decodeUsage(prefs.getString(KEY_USAGE, null)))
    /** Launch counts per app, used for the suggestions row in the drawer. Stays on the phone. */
    val usage: StateFlow<Map<String, Int>> = _usage.asStateFlow()

    /** Work profile turned on/off, added or removed. */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    /** Last work-hours state we acted on; null until first seen (we don't act on launch). */
    private var lastWorkTime: Boolean? = null

    /** Measured by the home grid; used to size new widgets. */
    var cellWidthDp = 80f
    var cellHeightDp = 110f
    var currentPage = 0

    init {
        refresh()
        ContextCompat.registerReceiver(
            app,
            profileReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Auto pause / resume work apps at the edges of work hours.
        viewModelScope.launch {
            workTime.collect { applyWorkHours(it) }
        }
        // Keeps the emoji battery widget and previews current.
        EmojiBatteryPrefs.load(app)
        BatteryHub.start(app)
    }

    override fun onCleared() {
        BatteryHub.stop(getApplication())
        runCatching { getApplication<Application>().unregisterReceiver(profileReceiver) }
    }

    private fun applyWorkHours(isWork: Boolean) {
        val previous = lastWorkTime
        lastWorkTime = isWork
        if (previous == null || previous == isWork) return
        val s = _settings.value
        if (!s.workHours || !s.pauseWorkAfterHours || _workProfile.value == null) return
        if (!setWorkPaused(paused = !isWork, quiet = true)) lastWorkTime = previous // retry on next resume
    }

    /** Home came to the front: the moment Android lets a launcher pause or resume work apps. */
    fun onResume() {
        refresh()
        val prev = lastWorkTime
        val cur = workTime.value
        if (prev != null && prev != cur) {
            lastWorkTime = !cur
            applyWorkHours(cur)
        }
        meetingRefresh.value++
        refreshWeather()
        refreshNews()
        refreshSports()
        refreshStocks()
        ensureFlightWatch()
        if (radarPaused && _overlay.value == Overlay.Radar) startRadar(radarRadius)
        radarPaused = false
        // Back from Android's install screen without updating: offer it again.
        if (_update.value is UpdateState.Installing) {
            _update.value = UpdateState.Idle
            lastUpdateCheck = 0L
        }
        checkForUpdates()
    }

    /** Horizon went to the background: lock the hidden apps again. */
    fun onStop() {
        _hiddenUnlocked.value = false
        if (_overlay.value == Overlay.HiddenApps) _overlay.value = null
        // No live updates while Horizon isn't on screen.
        if (radarJob != null) { stopRadar(); radarPaused = true }
    }

    /**
     * Pause or resume all work apps. Android only allows this for the default Home app.
     * Returns false if the system refused.
     */
    fun setWorkPaused(paused: Boolean, quiet: Boolean = false): Boolean {
        val work = _workProfile.value ?: return false
        val ok = runCatching { userManager.requestQuietModeEnabled(paused, work.user) }
            .getOrElse {
                if (!quiet) toast("Make Horizon your default home app to pause work apps")
                false
            }
        refresh()
        return ok
    }

    fun refreshMeetings() { meetingRefresh.value++ }

    // ---------- Updates ----------

    val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var lastUpdateCheck = 0L

    /** Looks for a newer build on GitHub; automatic checks happen at most every 6 hours. */
    fun checkForUpdates(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        val busy = _update.value is UpdateState.Downloading || _update.value is UpdateState.Installing
        if (busy || (!manual && now - lastUpdateCheck < 6 * 60 * 60_000L)) return
        lastUpdateCheck = now
        if (manual) _update.value = UpdateState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            val latest = updater.latest()
            _update.value = when {
                latest == null -> if (manual) UpdateState.Failed("Couldn't reach GitHub. Check your connection") else _update.value.let { if (it is UpdateState.Checking) UpdateState.Idle else it }
                latest.build > updater.currentBuild() -> UpdateState.Available(latest)
                else -> if (manual) UpdateState.UpToDate else UpdateState.Idle
            }
        }
    }

    fun installUpdate() {
        val info = (_update.value as? UpdateState.Available)?.info
            ?: (_update.value as? UpdateState.Failed)?.let { return checkForUpdates(manual = true) }
            ?: return
        if (!updater.ensureInstallAllowed()) return
        _update.value = UpdateState.Downloading(info, 0f)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                updater.downloadAndInstall(info) { p -> _update.value = UpdateState.Downloading(info, p) }
            }.onSuccess {
                _update.value = UpdateState.Installing
            }.onFailure {
                _update.value = UpdateState.Failed("Download failed. Tap to try again")
            }
        }
    }

    // ---------- Weather ----------

    val weatherRepo = WeatherRepository(app)
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private var weatherFetchedAt = 0L
    private var weatherKey = ""

    /** Refreshes at most every 30 minutes, or right away when the city or units change. */
    fun refreshWeather(force: Boolean = false) {
        val s = _settings.value
        if (!s.showWeather) { _weather.value = null; return }
        val fahrenheit = when (s.weatherUnit) { "f" -> true; "c" -> false; else -> WeatherRepository.defaultFahrenheit() }
        val key = s.weatherCity.trim() + "|" + fahrenheit
        val stale = System.currentTimeMillis() - weatherFetchedAt > 30 * 60_000L
        if (!force && !stale && key == weatherKey) return
        if (s.weatherCity.isBlank() && !weatherRepo.hasLocationPermission()) return
        weatherKey = key
        weatherFetchedAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val w = runCatching { weatherRepo.fetch(s.weatherCity, fahrenheit) }.getOrNull()
            if (w != null) _weather.value = w else weatherFetchedAt = 0L // retry next time
        }
    }

    // ---------- News ----------

    val newsRepo = NewsRepository(app)
    private val _news = MutableStateFlow<List<NewsStory>>(emptyList())
    val news: StateFlow<List<NewsStory>> = _news.asStateFlow()
    private val _newsLoading = MutableStateFlow(false)
    val newsLoading: StateFlow<Boolean> = _newsLoading.asStateFlow()
    private var newsFetchedAt = 0L
    private var newsKey = ""

    /** Refreshes at most every 30 minutes, or right away when your topics or feeds change. */
    fun refreshNews(force: Boolean = false) {
        val s = _settings.value
        if (!s.showNews) { _news.value = emptyList(); newsKey = ""; return }
        val key = s.newsTopics.sorted().joinToString() + "|" + s.newsFeeds.joinToString() + "|" + s.newsKeywords.joinToString()
        val stale = System.currentTimeMillis() - newsFetchedAt > 30 * 60_000L
        if (!force && !stale && key == newsKey) {
            _news.value = _news.value.filter { it.source !in s.newsHidden }
            return
        }
        if (_newsLoading.value) return
        newsKey = key
        newsFetchedAt = System.currentTimeMillis()
        _newsLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching {
                newsRepo.load(s.newsTopics, s.newsFeeds, s.newsHidden, s.newsDismissed.toSet(), s.newsLikedSources, s.newsKeywords)
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) _news.value = list else newsFetchedAt = 0L // retry next time
            _newsLoading.value = false
        }
    }

    /** Heart a story: it's remembered, and its source shows up more. */
    fun toggleHeart(story: NewsStory) {
        val on = story.link !in _settings.value.newsHearts
        updateSettings {
            if (on) it.copy(newsHearts = (it.newsHearts + story.link).takeLast(300), newsLikedSources = it.newsLikedSources + story.source)
            else it.copy(newsHearts = it.newsHearts - story.link)
        }
        if (on) toast("You'll see more from ${story.source}")
    }

    /** "Not interested": the story goes away and won't come back. */
    fun dismissStory(story: NewsStory) {
        updateSettings { it.copy(newsDismissed = (it.newsDismissed + story.link).takeLast(400)) }
        _news.value = _news.value.filter { it.link != story.link }
    }

    fun hideNewsSource(source: String) {
        updateSettings { it.copy(newsHidden = it.newsHidden + source, newsLikedSources = it.newsLikedSources - source) }
        _news.value = _news.value.filter { it.source != source }
        toast("Stories from $source hidden")
    }

    // ---------- Flight radar ----------

    data class FlightsState(
        val center: Pair<Double, Double>? = null,
        val planes: List<Aircraft> = emptyList(),
        val routes: Map<String, FlightRoute?> = emptyMap(),
        val radiusNm: Int = 0,
        val updatedAt: Long = 0L,
        val loading: Boolean = false,
        /** "location", "network" or null. */
        val problem: String? = null,
    )

    val flightRepo = FlightRepository()
    private val _flights = MutableStateFlow(FlightsState())
    val flights: StateFlow<FlightsState> = _flights.asStateFlow()
    private var radarJob: kotlinx.coroutines.Job? = null
    private var radarPaused = false
    private var radarRadius = 25
    private var flightCenterAt = 0L

    /** Loads planes once (for the Today card), unless it was done in the last minute. */
    fun refreshFlights(radiusNm: Int, force: Boolean = false) {
        val f = _flights.value
        if (!force && f.radiusNm >= radiusNm && System.currentTimeMillis() - f.updatedAt < 60_000L) return
        if (f.loading) return
        viewModelScope.launch { loadFlights(radiusNm) }
    }

    /** Live updates every 5 seconds while the radar is open. */
    fun startRadar(radiusNm: Int) {
        radarRadius = radiusNm
        radarJob?.cancel()
        radarJob = viewModelScope.launch {
            while (true) {
                loadFlights(radiusNm)
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    fun stopRadar() {
        radarJob?.cancel()
        radarJob = null
    }

    private suspend fun loadFlights(radiusNm: Int) {
        _flights.value = _flights.value.copy(loading = true)
        val s = _settings.value
        // Your position is looked up again every few minutes, not on every refresh.
        var center = _flights.value.center
        if (center == null || System.currentTimeMillis() - flightCenterAt > 5 * 60_000L) {
            val c = runCatching { weatherRepo.coordinates(s.weatherCity) }.getOrNull()
            if (c != null) {
                center = c.first to c.second
                flightCenterAt = System.currentTimeMillis()
            }
        }
        if (center == null) {
            _flights.value = _flights.value.copy(loading = false, problem = "location")
            return
        }
        val planes = flightRepo.nearby(center.first, center.second, radiusNm)
        if (planes == null) {
            _flights.value = _flights.value.copy(center = center, loading = false, problem = "network")
            return
        }
        val inRange = planes.filter {
            FlightRepository.distanceNm(center.first, center.second, it.lat, it.lon) <= radiusNm
        }
        _flights.value = _flights.value.copy(
            center = center,
            planes = inRange,
            radiusNm = radiusNm,
            updatedAt = System.currentTimeMillis(),
            loading = false,
            problem = null,
        )
        val routes = runCatching { flightRepo.routesFor(inRange) }.getOrNull()
        if (routes != null) _flights.value = _flights.value.copy(routes = _flights.value.routes + routes)
    }

    // ---------- Following a flight, and planes overhead ----------

    data class FollowedFlight(
        val callsign: String,
        val aircraft: Aircraft?,
        val route: FlightRoute?,
        /** Distance from you in nautical miles, when your position is known. */
        val distanceNm: Double?,
        val updatedAt: Long,
        /** Looked, and it isn't flying right now. */
        val notFlying: Boolean,
    )

    private val _followed = MutableStateFlow<FollowedFlight?>(null)
    val followed: StateFlow<FollowedFlight?> = _followed.asStateFlow()
    private var watchJob: kotlinx.coroutines.Job? = null
    private var followAirborne = false
    private val overheadSeen = HashMap<String, Long>()

    /** Starts following a flight ("UA 123", "UAL123" or a registration). */
    fun followFlight(input: String) {
        val cs = FlightRepository.callsignFor(input)
        if (cs.length < 3) return
        followAirborne = false
        _followed.value = null
        updateSettings { it.copy(followFlight = cs) }
        viewModelScope.launch { refreshFollowed() }
        ensureFlightWatch()
    }

    fun stopFollowing() {
        updateSettings { it.copy(followFlight = "") }
        _followed.value = null
        followAirborne = false
    }

    fun refreshFollowedNow() {
        viewModelScope.launch { refreshFollowed() }
    }

    private suspend fun refreshFollowed() {
        val cs = _settings.value.followFlight
        if (cs.isBlank()) { _followed.value = null; return }
        val found = flightRepo.byCallsign(cs) ?: return // network trouble: keep what we had
        val a = found.maxByOrNull { it.seenAt }
        val center = _flights.value.center ?: runCatching { weatherRepo.coordinates(_settings.value.weatherCity) }.getOrNull()?.let { it.first to it.second }
        val route = a?.let { runCatching { flightRepo.routesFor(listOf(it))[it.callsign] }.getOrNull() }
        val dist = if (a != null && center != null) FlightRepository.distanceNm(center.first, center.second, a.lat, a.lon) else null
        val before = _followed.value
        _followed.value = FollowedFlight(cs, a, route ?: before?.route, dist, System.currentTimeMillis(), notFlying = a == null || a.onGround)
        // Landing: it was in the air, now it's on the ground (or gone after coming down low).
        val airborne = a != null && !a.onGround && (a.altitude ?: 0) > 1500
        val lowBefore = before?.aircraft?.let { (it.altitude ?: 0) < 4000 } == true
        if (airborne) followAirborne = true
        else if (followAirborne && (a?.onGround == true || (a == null && lowBefore))) {
            followAirborne = false
            val name = FlightRepository.flightNumber(cs) ?: cs
            val where = (route ?: before?.route)?.takeIf { it.plausible }?.let { r -> " in ${r.toCity ?: r.toCode}" }.orEmpty()
            notifyFlight(cs.hashCode(), "$name has landed$where", "Tap to open the flight radar")
        }
    }

    /** Checks once a minute while your screen is on, only when you follow a flight or want overhead alerts. */
    fun ensureFlightWatch() {
        val s = _settings.value
        if (!s.overheadAlerts && s.followFlight.isBlank()) { watchJob?.cancel(); watchJob = null; return }
        if (watchJob?.isActive == true) return
        val power = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
        watchJob = viewModelScope.launch {
            while (true) {
                val st = _settings.value
                if (!st.overheadAlerts && st.followFlight.isBlank()) break
                if (power?.isInteractive != false) {
                    if (st.followFlight.isNotBlank()) runCatching { refreshFollowed() }
                    if (st.overheadAlerts) runCatching { checkOverhead() }
                }
                delay(60_000)
            }
            watchJob = null
        }
    }

    private suspend fun checkOverhead() {
        val c = runCatching { weatherRepo.coordinates(_settings.value.weatherCity) }.getOrNull() ?: return
        val planes = flightRepo.nearby(c.first, c.second, 4) ?: return
        val now = System.currentTimeMillis()
        overheadSeen.entries.removeAll { now - it.value > 40 * 60_000L }
        val low = planes.filter {
            !it.onGround && (it.altitude ?: 99_999) in 300..15_000 &&
                FlightRepository.distanceNm(c.first, c.second, it.lat, it.lon) <= 2.5 && it.hex !in overheadSeen
        }.minByOrNull { FlightRepository.distanceNm(c.first, c.second, it.lat, it.lon) } ?: return
        overheadSeen[low.hex] = now
        val route = runCatching { flightRepo.routesFor(listOf(low))[low.callsign] }.getOrNull()?.takeIf { it.plausible }
        val name = FlightRepository.flightNumber(low.callsign) ?: low.displayName
        val who = FlightRepository.airlineOf(low.callsign) ?: low.description ?: low.type
        val bits = listOfNotNull(
            route?.let { "${it.fromCode} → ${it.toCode}" },
            low.altitude?.let { "%,d ft".format(Locale.getDefault(), it) },
            who,
        )
        notifyFlight(low.hex.hashCode(), "$name is flying over you", bits.joinToString(" · "))
    }

    private fun notifyFlight(id: Int, title: String, text: String) {
        val ctx = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            android.app.NotificationChannel("flights", "Flights", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Planes over you and flights you follow" },
        )
        val open = android.app.PendingIntent.getActivity(
            ctx, 7,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "radar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "flights")
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    // ---------- Sports and stocks ----------

    val sportsRepo = SportsRepository()
    val stocksRepo = StocksRepository()
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()
    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes.asStateFlow()
    private var gamesAt = 0L
    private var quotesAt = 0L
    private var gamesKey = ""
    private var quotesKey = ""

    /** Every minute while a game is on, otherwise every 10 minutes. */
    fun refreshSports(force: Boolean = false) {
        val teams = _settings.value.sportsTeams.mapNotNull { FollowedTeam.decode(it) }
        if (teams.isEmpty()) { _games.value = emptyList(); gamesKey = ""; return }
        val key = teams.joinToString { it.id + it.league }
        val every = if (_games.value.any { it.live }) 60_000L else 10 * 60_000L
        if (!force && key == gamesKey && System.currentTimeMillis() - gamesAt < every) return
        gamesKey = key
        gamesAt = System.currentTimeMillis()
        viewModelScope.launch {
            val g = sportsRepo.games(teams)
            if (g.isNotEmpty() || _games.value.isEmpty()) _games.value = g else gamesAt = 0L
        }
    }

    /** Every 5 minutes. */
    fun refreshStocks(force: Boolean = false) {
        val list = _settings.value.stocks
        if (list.isEmpty()) { _quotes.value = emptyList(); quotesKey = ""; return }
        val key = list.joinToString()
        if (!force && key == quotesKey && System.currentTimeMillis() - quotesAt < 5 * 60_000L) return
        quotesKey = key
        quotesAt = System.currentTimeMillis()
        viewModelScope.launch {
            val q = stocksRepo.quotes(list)
            if (q.isNotEmpty()) _quotes.value = list.mapNotNull { s -> q.firstOrNull { it.symbol.equals(s, true) } } .ifEmpty { q }
            else quotesAt = 0L
        }
    }

    fun addStock(text: String) {
        viewModelScope.launch {
            val sym = stocksRepo.lookup(text)
            if (sym == null) { toast("Couldn't find \u201C${text.trim()}\u201D"); return@launch }
            updateSettings { if (sym in it.stocks) it else it.copy(stocks = it.stocks + sym) }
            refreshStocks(force = true)
        }
    }

    // ---------- Discover extras ----------

    fun toggleSaved(story: NewsStory) {
        val saved = _settings.value.newsSaved.any { it.link == story.link }
        updateSettings {
            if (saved) it.copy(newsSaved = it.newsSaved.filter { s -> s.link != story.link })
            else it.copy(newsSaved = (it.newsSaved + story).takeLast(200))
        }
        toast(if (saved) "Removed from Saved" else "Saved for later")
    }

    // ---------- Focus ----------

    /** On by hand, or automatically during work hours if you chose that. */
    val focusActive: StateFlow<Boolean> = combine(settings, workTime) { s, w -> s.focusOn || (s.focusDuringWork && w) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var focusBypassKey: String? = null
    private var focusBypassAt = 0L

    /** First tap on a distracting app during Focus asks; a second tap within 4 s opens it. */
    private fun focusAllows(app: AppEntry): Boolean {
        if (!focusActive.value || app.key !in _settings.value.focusApps) return true
        val now = System.currentTimeMillis()
        if (focusBypassKey == app.key && now - focusBypassAt < 4000) {
            focusBypassKey = null
            return true
        }
        focusBypassKey = app.key
        focusBypassAt = now
        toast("Focus is on. Tap ${app.label} again to open it anyway")
        return false
    }

    fun setFocus(on: Boolean) = updateSettings { it.copy(focusOn = on) }

    // ---------- Hidden apps (locked) ----------

    private val _hiddenUnlocked = MutableStateFlow(false)
    /** True after fingerprint / screen lock; locks again when Horizon goes to the background. */
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked.asStateFlow()
    fun setHiddenUnlocked(v: Boolean) { _hiddenUnlocked.value = v }
    /** Minimal mode's starting favourites: your dock, then apps on the first Home page (up to 7). */
    fun defaultFavorites(): List<String> =
        (_dock.value + _items.value.filter { it.page == 0 && it.type == TYPE_APP }.flatMap { it.apps })
            .distinct()
            .take(7)

    fun hideApp(app: AppEntry) = updateSettings { it.copy(hidden = it.hidden + app.key) }
    fun unhideApp(app: AppEntry) = updateSettings { it.copy(hidden = it.hidden - app.key) }

    /** Put an app on the Work tab, or take it off. Work-profile apps are always on Work. */
    fun setWorkApp(key: String, onWork: Boolean) =
        updateSettings { it.copy(workApps = if (onWork) it.workApps + key else it.workApps - key) }

    // ---------- Apps ----------

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { loadApps() }.getOrNull() } ?: return@launch
            _workProfile.value = withContext(Dispatchers.IO) {
                runCatching {
                    val me = Process.myUserHandle()
                    userManager.userProfiles.firstOrNull { it != me }?.let { WorkProfile(it, userManager.isQuietModeEnabled(it)) }
                }.getOrNull()
            }
            _apps.value = list
            _appIndex.value = list.associateBy { it.key }
            if (list.isNotEmpty()) {
                ensureDefaultDock(list)
                cleanup(list)
            }
        }
    }

    private val iconCache by lazy { IconCache(getApplication()) }

    private fun loadApps(): List<AppEntry> {
        val ctx = getApplication<Application>()
        val px = (72 * ctx.resources.displayMetrics.density).toInt()
        val s = _settings.value
        // The icon pack is only opened if an icon actually needs drawing (cache miss).
        val pack by lazy { s.iconPack.takeIf { it.isNotEmpty() }?.let { IconPack.load(ctx, it) } }
        val renderer by lazy { IconRenderer(ctx, px, IconShape.of(s.iconShape), s.themedIcons, pack) }
        // Everything that changes how icons look; a change here means fresh icons.
        val packVersion = s.iconPack.takeIf { it.isNotEmpty() }?.let { p ->
            runCatching { ctx.packageManager.getApplicationInfo(p, 0).sourceDir }.getOrNull()
        }
        val themeColors = if (s.themedIcons && android.os.Build.VERSION.SDK_INT >= 31) {
            "${ctx.getColor(android.R.color.system_accent1_800)}/${ctx.getColor(android.R.color.system_accent1_100)}"
        } else ""
        val style = "${s.iconShape}|${s.iconPack}|$packVersion|${s.themedIcons}|$themeColors|$px"
        val usedKeys = ArrayList<String>()
        val me = Process.myUserHandle()
        val quiet = userManager.userProfiles.filter { it != me && userManager.isQuietModeEnabled(it) }.toSet()
        return userManager.userProfiles
            .flatMap { user -> launcherApps.getActivityList(null, user) }
            .filter { it.componentName.packageName != ctx.packageName }
            .map { info ->
                val serialNo = userManager.getSerialNumberForUser(info.user)
                val appKey = info.componentName.flattenToString() + "#" + serialNo
                val custom = s.appIcons[appKey]?.let { name ->
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(java.io.File(ctx.filesDir, "app_icons/$name").path)
                    }.getOrNull()?.let { renderer.custom(it) }
                }
                AppEntry(
                    label = s.appNames[appKey]?.takeIf { it.isNotBlank() } ?: info.label.toString(),
                    originalLabel = info.label.toString(),
                    showLabel = appKey !in s.noLabel,
                    group = AppGroup.of(info.applicationInfo, info.user != me).id,
                    component = info.componentName,
                    user = info.user,
                    serial = userManager.getSerialNumberForUser(info.user),
                    icon = custom?.asImageBitmap() ?: run {
                        val serial = serialNo
                        val key = "${info.componentName.flattenToString()}#$serial|${info.applicationInfo.sourceDir}|$style"
                        usedKeys += key
                        iconCache.getOrPut(key) {
                            runCatching { renderer.render(info) }.getOrNull() ?: info.getBadgedIcon(0).toBitmap(px, px)
                        }.asImageBitmap()
                    },
                    isWork = info.user != me,
                    isPaused = info.user in quiet,
                )
            }
            .sortedBy { it.label.lowercase() }
            .also { iconCache.prune(usedKeys) }
    }

    private fun ensureDefaultDock(list: List<AppEntry>) {
        if (prefs.contains(KEY_DOCK)) return
        val mine = userManager.getSerialNumberForUser(Process.myUserHandle())
        val keys = DEFAULT_DOCK.mapNotNull { pkg ->
            list.firstOrNull { it.packageName == pkg && it.serial == mine }?.key
        }
        setDock(keys)
    }

    /** Drop home and dock entries for apps that were uninstalled. */
    private fun cleanup(list: List<AppEntry>) {
        val keys = list.mapTo(HashSet()) { it.key }
        val dock = _dock.value.filter { it in keys }
        if (dock != _dock.value) setDock(dock)

        val items = _items.value.mapNotNull { item ->
            when (item.type) {
                TYPE_APP -> item.takeIf { it.apps.firstOrNull() in keys }
                TYPE_FOLDER -> {
                    val rest = item.apps.filter { it in keys }
                    when (rest.size) {
                        0 -> null
                        1 -> item.copy(type = TYPE_APP, apps = rest, name = "")
                        else -> item.copy(apps = rest)
                    }
                }
                // Widgets that no longer exist (for example after moving to a new phone).
                TYPE_WIDGET -> {
                    val alive = (listOf(item.widgetId) + item.stack).filter { id -> widgetManager.getAppWidgetInfo(id) != null }
                    when {
                        alive.isEmpty() -> null
                        alive.first() == item.widgetId && alive.size == 1 + item.stack.size -> item
                        else -> item.copy(widgetId = alive.first(), stack = alive.drop(1))
                    }
                }
                else -> item
            }
        }
        if (items != _items.value) setItems(items)
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null, options: android.os.Bundle? = null) {
        if (app.isPaused) {
            // Same as Pixel: tapping a paused work app offers to turn work apps back on.
            setWorkPaused(false)
            return
        }
        if (!focusAllows(app)) return
        recordLaunch(app.key)
        runCatching { launcherApps.startMainActivity(app.component, app.user, sourceBounds, options) }
            .onFailure { runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) } }
    }

    private val _hourlyUsage = MutableStateFlow(decodeUsage(prefs.getString(KEY_USAGE_HOURS, null)))
    /** Launch counts per app and time of day ("key@bucket"), so suggestions follow your day. */
    val hourlyUsage: StateFlow<Map<String, Int>> = _hourlyUsage.asStateFlow()

    private fun recordLaunch(key: String) {
        val h = _hourlyUsage.value.toMutableMap()
        val hk = key + "@" + hourBucket(java.time.LocalTime.now().hour)
        h[hk] = (h[hk] ?: 0) + 1
        val ht = h.entries.sortedByDescending { it.value }.take(600).associate { it.key to it.value }
        _hourlyUsage.value = ht
        prefs.edit().putString(KEY_USAGE_HOURS, JSONObject(ht as Map<*, *>).toString()).apply()
        val counts = _usage.value.toMutableMap()
        counts[key] = (counts[key] ?: 0) + 1
        val trimmed = counts.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        _usage.value = trimmed
        prefs.edit().putString(KEY_USAGE, JSONObject(trimmed as Map<*, *>).toString()).apply()
    }

    fun clearSuggestions() {
        _usage.value = emptyMap()
        _hourlyUsage.value = emptyMap()
        prefs.edit().remove(KEY_USAGE).remove(KEY_USAGE_HOURS).apply()
    }

    private fun decodeUsage(json: String?): Map<String, Int> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    // ---------- Settings ----------

    fun openSettings(open: Boolean) {
        _settingsOpen.value = open
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.edit().putString(KEY_SETTINGS, new.toJson().toString()).apply()
        if (old.themedIcons != new.themedIcons || old.iconShape != new.iconShape || old.iconPack != new.iconPack ||
            old.appNames != new.appNames || old.appIcons != new.appIcons || old.noLabel != new.noLabel
        ) refresh()
        if (old.showWeather != new.showWeather || old.weatherCity != new.weatherCity || old.weatherUnit != new.weatherUnit) {
            refreshWeather(force = true)
        }
        if (old.showNews != new.showNews || old.newsTopics != new.newsTopics || old.newsFeeds != new.newsFeeds ||
            old.newsKeywords != new.newsKeywords
        ) {
            refreshNews(force = true)
        }
        if (old.sportsTeams != new.sportsTeams) refreshSports(force = true)
        if (old.stocks != new.stocks) refreshStocks(force = true)
        if (old.overheadAlerts != new.overheadAlerts || old.followFlight != new.followFlight) ensureFlightWatch()
    }

    fun exportLayout(): String {
        // Your own app icons go in the file too, so a restore brings them back.
        val icons = JSONObject()
        val dir = java.io.File(getApplication<Application>().filesDir, "app_icons")
        _settings.value.appIcons.values.forEach { name ->
            runCatching {
                val f = java.io.File(dir, name)
                if (f.exists()) icons.put(name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
            }
        }
        return JSONObject()
            .put("app", "Horizon")
            .put("version", 2)
            .put("items", JSONArray(encodeItems(_items.value)))
            .put("dock", JSONArray(_dock.value))
            .put("settings", _settings.value.toJson())
            .put("usage", JSONObject(_usage.value as Map<*, *>))
            .put("usageHours", JSONObject(_hourlyUsage.value as Map<*, *>))
            .put("icons", icons)
            .toString(2)
    }

    fun importLayout(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        val items = decodeItems(o.getJSONArray("items").toString())
            .filter { it.type != TYPE_WIDGET || widgetManager.getAppWidgetInfo(it.widgetId) != null }
        val dock = decodeKeys(o.getJSONArray("dock").toString())
        setItems(items)
        setDock(dock)
        o.optJSONObject("icons")?.let { icons ->
            val dir = java.io.File(getApplication<Application>().filesDir, "app_icons").apply { mkdirs() }
            icons.keys().forEach { name ->
                runCatching {
                    if (!name.contains('/')) java.io.File(dir, name).writeBytes(android.util.Base64.decode(icons.getString(name), android.util.Base64.NO_WRAP))
                }
            }
        }
        o.optJSONObject("usage")?.let { u ->
            val m = decodeUsage(u.toString())
            _usage.value = m
            prefs.edit().putString(KEY_USAGE, u.toString()).apply()
        }
        o.optJSONObject("usageHours")?.let { u ->
            _hourlyUsage.value = decodeUsage(u.toString())
            prefs.edit().putString(KEY_USAGE_HOURS, u.toString()).apply()
        }
        o.optJSONObject("settings")?.let { js -> updateSettings { LauncherSettings.fromJson(js) } }
        val list = _apps.value
        if (list.isNotEmpty()) cleanup(list)
        true
    }.getOrDefault(false)

    /**
     * The app's shortcuts (Compose, Navigate home…) for its long-press menu, as Pixel shows them.
     * Android only gives these to the default Home app.
     */
    fun shortcutsFor(app: AppEntry): List<MenuAction> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        launcherApps.getShortcuts(query, app.user).orEmpty()
            .filter { it.isEnabled }
            .sortedWith(compareBy<android.content.pm.ShortcutInfo> { if (it.isDeclaredInManifest) 0 else 1 }.thenBy { it.rank })
            .take(4)
            .map { info ->
                MenuAction((info.shortLabel ?: info.longLabel ?: "Shortcut").toString(), isShortcut = true) {
                    runCatching { launcherApps.startShortcut(info, null, null) }
                        .onFailure { toast("That shortcut isn't available") }
                }
            }
    }.getOrDefault(emptyList())

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    // ---------- Overlays ----------

    // ---------- Per-app tweaks ----------

    private val _customizing = MutableStateFlow<AppEntry?>(null)
    val customizing: StateFlow<AppEntry?> = _customizing.asStateFlow()

    fun customizeApp(app: AppEntry) {
        _customizing.value = app
        showOverlay(Overlay.Customize)
    }

    /** Saves a picture as this app's icon (kept in Horizon's own storage). */
    fun setAppIcon(key: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val ok = runCatching {
                val dir = java.io.File(ctx.filesDir, "app_icons").apply { mkdirs() }
                val name = Integer.toHexString(key.hashCode()) + "-" + System.currentTimeMillis() + ".png"
                val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching false
                val side = minOf(src.width, src.height)
                val scale = 256f / side
                val small = android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
                java.io.File(dir, name).outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                _settings.value.appIcons[key]?.let { java.io.File(dir, it).delete() }
                withContext(Dispatchers.Main) { updateSettings { it.copy(appIcons = it.appIcons + (key to name)) } }
                true
            }.getOrDefault(false)
            if (!ok) withContext(Dispatchers.Main) { toast("Couldn't use that picture") }
        }
    }

    fun resetApp(key: String) {
        _settings.value.appIcons[key]?.let { java.io.File(getApplication<Application>().filesDir, "app_icons/$it").delete() }
        updateSettings { it.copy(appNames = it.appNames - key, appIcons = it.appIcons - key, noLabel = it.noLabel - key) }
    }

    // ---------- Emoji battery ----------

    private val _emojiWidgetSignal = MutableStateFlow(0)
    /** Goes up when you ask to put the emoji battery widget on your home screen. */
    val emojiWidgetSignal: StateFlow<Int> = _emojiWidgetSignal.asStateFlow()
    fun addEmojiBatteryWidget() {
        showOverlay(null)
        openSettings(false)
        _emojiWidgetSignal.value++
    }

    private val _todaySignal = MutableStateFlow(0)
    /** Goes up by one each time something asks for the Today page. */
    val todaySignal: StateFlow<Int> = _todaySignal.asStateFlow()
    fun openToday() {
        _drawerOpen.value = false
        _todaySignal.value++
    }

    fun openDrawer(focusSearch: Boolean = false) {
        _searchFocus.value = focusSearch
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
        _searchFocus.value = false
    }

    fun openFolder(id: Long) {
        _openFolder.value = id
    }

    fun closeFolder() {
        _openFolder.value = null
    }

    fun showWidgetPicker(show: Boolean) {
        _widgetPicker.value = show
    }

    /** Full-screen helpers opened from the add wheel (or Settings, for Icon style). */
    enum class Overlay { Wheel, AddApp, IconStyle, HiddenApps, FocusApps, Favorites, ClockFaces, Radar, Customize, EmojiBattery }

    /** Choose the clock face (keeps the older Compact / Dial switches in step). */
    fun setClockFace(face: ClockFace) = updateSettings {
        it.copy(clockFace = face.id, compactClock = face == ClockFace.Compact, dialClock = face.dialVariant != null)
    }

    private val _overlay = MutableStateFlow<Overlay?>(null)
    val overlay: StateFlow<Overlay?> = _overlay.asStateFlow()

    fun showOverlay(o: Overlay?) {
        _overlay.value = o
    }

    fun iconPacks(): List<IconPackInfo> = installedIconPacks(getApplication())

    fun onHomePressed(alreadyOnTop: Boolean) {
        val wasOverlay = _drawerOpen.value || _openFolder.value != null || _widgetPicker.value ||
            _settingsOpen.value || _overlay.value != null
        showOverlay(null)
        closeDrawer()
        closeFolder()
        showWidgetPicker(false)
        openSettings(false)
        if (alreadyOnTop && !wasOverlay) _homeSignal.value += 1
    }

    // ---------- Persistence ----------

    private fun setItems(new: List<HomeItem>) {
        val compact = compactPages(new)
        _items.value = compact
        prefs.edit().putString(KEY_ITEMS, encodeItems(compact)).apply()
        val open = _openFolder.value
        if (open != null && compact.none { it.id == open && it.type == TYPE_FOLDER }) closeFolder()
    }

    private fun setDock(new: List<String>) {
        _dock.value = new
        prefs.edit().putString(KEY_DOCK, encodeKeys(new)).apply()
    }

    private fun nextId(items: List<HomeItem>) = (items.maxOfOrNull { it.id } ?: 0L) + 1L

    fun toast(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
    }

    // ---------- Home edits ----------

    fun addToHome(app: AppEntry) {
        val items = _items.value
        if (items.any { it.type == TYPE_APP && it.apps.firstOrNull() == app.key }) {
            toast("${app.label} is already on your home screen")
            return
        }
        val (page, x, y) = findFreeAnywhere(items, 0, 1, 1)
        setItems(items + HomeItem(nextId(items), TYPE_APP, page, x, y, apps = listOf(app.key)))
        toast("Added to home screen")
    }

    fun isInDock(key: String) = key in _dock.value

    fun toggleDock(key: String) {
        val dock = _dock.value
        when {
            key in dock -> setDock(dock - key)
            dock.size >= MAX_DOCK -> toast("The dock is full")
            else -> setDock(dock + key)
        }
    }

    fun removeItem(id: Long) = drop(DragPayload.FromHome(id), DropTarget.Remove)

    fun removeFromDock(index: Int) = drop(DragPayload.FromDock(index), DropTarget.Remove)

    fun removeFromFolder(folderId: Long, index: Int) {
        val items = _items.value
        val folder = items.firstOrNull { it.id == folderId } ?: return
        val key = folder.apps.getOrNull(index) ?: return
        val (without, dock) = withoutSource(DragPayload.FromFolder(folderId, index), items, _dock.value)
        val (page, x, y) = findFree(without, folder.page, 1, 1, folder.x, folder.y)
            ?.let { Triple(folder.page, it.first, it.second) }
            ?: findFreeAnywhere(without, folder.page, 1, 1)
        setItems(without + HomeItem(nextId(without), TYPE_APP, page, x, y, apps = listOf(key)))
        setDock(dock)
    }

    fun renameFolder(id: Long, name: String) {
        setItems(_items.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun resizeWidget(id: Long, sx: Int, sy: Int) {
        val items = _items.value
        val item = items.firstOrNull { it.id == id } ?: return
        val x = item.x.coerceAtMost(GRID_COLS - sx)
        val y = item.y.coerceAtMost(GRID_ROWS - sy)
        if (fits(items, item.page, x, y, sx, sy, id)) {
            setItems(items.map { if (it.id == id) it.copy(x = x, y = y, spanX = sx, spanY = sy) else it })
        } else {
            toast("Not enough room for that size")
        }
    }

    // ---------- Widgets ----------

    fun spansFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val density = getApplication<Application>().resources.displayMetrics.density
        var sx = ceil(info.minWidth / density / cellWidthDp).toInt()
        var sy = ceil(info.minHeight / density / cellHeightDp).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            sx = info.targetCellWidth
            sy = info.targetCellHeight
        }
        return sx.coerceIn(1, GRID_COLS) to sy.coerceIn(1, GRID_ROWS)
    }

    // ---------- Widget stacks ----------

    /** The stack waiting for its next widget (set by "Add widget to stack"). */
    private var stackTarget: Long? = null
    private var stackTargetAt = 0L

    fun startAddingToStack(itemId: Long) {
        stackTarget = itemId
        stackTargetAt = System.currentTimeMillis()
        showWidgetPicker(true)
    }

    fun cancelAddingToStack() { stackTarget = null }

    /** Take one widget out of a stack; the rest stay. */
    fun removeFromStack(itemId: Long, widgetId: Int) {
        val items = _items.value
        val it0 = items.firstOrNull { it.id == itemId } ?: return
        val all = listOf(it0.widgetId) + it0.stack
        val left = all - widgetId
        runCatching { widgetHost.deleteAppWidgetId(widgetId) }
        if (left.isEmpty()) {
            setItems(items - it0)
            return
        }
        setItems(items.map { if (it.id == itemId) it.copy(widgetId = left.first(), stack = left.drop(1)) else it })
    }

    fun placeWidget(widgetId: Int, info: AppWidgetProviderInfo) {
        val items = _items.value
        // Going into a stack? (only if asked for in the last few minutes)
        val target = stackTarget?.takeIf { System.currentTimeMillis() - stackTargetAt < 5 * 60_000L }
        stackTarget = null
        if (target != null) {
            val host = items.firstOrNull { it.id == target && it.type == TYPE_WIDGET }
            if (host != null) {
                setItems(items.map { if (it.id == target) it.copy(stack = it.stack + widgetId) else it })
                toast("Added to the stack. Swipe up or down on it to switch")
                return
            }
        }
        val (sx, sy) = spansFor(info)
        val (page, x, y) = findFreeAnywhere(items, currentPage, sx, sy)
        setItems(items + HomeItem(nextId(items), TYPE_WIDGET, page, x, y, sx, sy, widgetId = widgetId))
        if (page != currentPage) toast("Widget added to page ${page + 1}")
    }

    // ---------- Drag and drop ----------

    private fun withoutSource(
        p: DragPayload,
        items: List<HomeItem>,
        dock: List<String>,
    ): Pair<List<HomeItem>, List<String>> = when (p) {
        is DragPayload.FromDrawer -> items to dock
        is DragPayload.FromDock -> items to dock.filterIndexed { i, _ -> i != p.index }
        is DragPayload.FromHome -> items.filter { it.id != p.itemId } to dock
        is DragPayload.FromFolder -> items.mapNotNull { f ->
            if (f.id != p.folderId) {
                f
            } else {
                val rest = f.apps.filterIndexed { i, _ -> i != p.index }
                when (rest.size) {
                    0 -> null
                    1 -> f.copy(type = TYPE_APP, apps = rest, name = "")
                    else -> f.copy(apps = rest)
                }
            }
        } to dock
    }

    fun drop(payload: DragPayload, target: DropTarget) {
        val items = _items.value
        val dock = _dock.value
        val dragged = (payload as? DragPayload.FromHome)?.let { p -> items.firstOrNull { it.id == p.itemId } }
        val appKey: String? = when (payload) {
            is DragPayload.FromDrawer -> payload.key
            is DragPayload.FromDock -> dock.getOrNull(payload.index)
            is DragPayload.FromFolder -> items.firstOrNull { it.id == payload.folderId }?.apps?.getOrNull(payload.index)
            is DragPayload.FromHome -> dragged?.takeIf { it.type == TYPE_APP }?.apps?.firstOrNull()
        }

        when (target) {
            DropTarget.None -> Unit

            DropTarget.Remove -> {
                if (payload is DragPayload.FromDrawer) return
                val (ni, nd) = withoutSource(payload, items, dock)
                if (dragged?.type == TYPE_WIDGET) {
                    (listOf(dragged.widgetId) + dragged.stack).forEach { id -> runCatching { widgetHost.deleteAppWidgetId(id) } }
                }
                setItems(ni)
                setDock(nd)
            }

            is DropTarget.Dock -> {
                val key = appKey ?: run {
                    toast("Only apps can go in the dock")
                    return
                }
                if (payload is DragPayload.FromDock) {
                    val m = dock.toMutableList()
                    val moved = m.removeAt(payload.index)
                    val idx = (if (target.index > payload.index) target.index - 1 else target.index).coerceIn(0, m.size)
                    m.add(idx, moved)
                    setDock(m)
                    return
                }
                val (ni, nd0) = withoutSource(payload, items, dock)
                val nd = nd0.filter { it != key }.toMutableList()
                if (nd.size >= MAX_DOCK) {
                    toast("The dock is full")
                    return
                }
                nd.add(target.index.coerceIn(0, nd.size), key)
                setItems(ni)
                setDock(nd)
            }

            is DropTarget.Home -> {
                val under = items.firstOrNull {
                    it.id != dragged?.id && it.page == target.page && it.covers(target.x, target.y)
                }

                // Dropping an app onto an app or folder makes or grows a folder.
                if (appKey != null && under != null && (under.type == TYPE_APP || under.type == TYPE_FOLDER)) {
                    if (payload is DragPayload.FromFolder && under.id == payload.folderId) return
                    val (ni, nd) = withoutSource(payload, items, dock)
                    val tgt = ni.firstOrNull { it.id == under.id } ?: return
                    val merged = when {
                        tgt.type == TYPE_FOLDER -> tgt.copy(apps = (tgt.apps - appKey) + appKey)
                        tgt.apps.firstOrNull() == appKey -> tgt
                        else -> tgt.copy(type = TYPE_FOLDER, apps = tgt.apps + appKey, name = "Folder")
                    }
                    setItems(ni.map { if (it.id == merged.id) merged else it })
                    setDock(nd)
                    return
                }

                val sx = dragged?.spanX ?: 1
                val sy = dragged?.spanY ?: 1
                val (ni, nd) = withoutSource(payload, items, dock)
                val candidate: Pair<Int, Int>? =
                    if (fits(ni, target.page, target.x, target.y, sx, sy)) {
                        target.x to target.y
                    } else {
                        findFree(ni, target.page, sx, sy, target.x, target.y)
                    }
                if (candidate == null) {
                    toast("No room on this page")
                    return
                }
                val spot: Pair<Int, Int> = candidate
                val newItem = dragged?.copy(page = target.page, x = spot.first, y = spot.second)
                    ?: HomeItem(
                        nextId(ni), TYPE_APP, target.page, spot.first, spot.second,
                        apps = listOf(appKey ?: return),
                    )
                setItems(ni + newItem)
                setDock(nd)
            }
        }
    }

    companion object {
        const val WIDGET_HOST_ID = 1024
        private const val KEY_ITEMS = "home_items"
        private const val KEY_DOCK = "dock_keys"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_USAGE = "usage"
        private const val KEY_USAGE_HOURS = "usage_hours"
        private val DEFAULT_DOCK = listOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.chrome",
            "com.google.android.GoogleCamera",
        )
    }
}
