package com.horizon.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the Dynamic Island can show. Filled in by [IslandListener], which Android runs
 * inside Horizon's own process once notification access is allowed.
 */
object IslandHub {

    data class Media(
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playing: Boolean,
        /** Position in ms at [positionAt] (elapsedRealtime). */
        val position: Long,
        val positionAt: Long,
        val speed: Float,
        val duration: Long,
        val packageName: String,
        val controller: MediaController,
    ) {
        fun positionNow(): Long {
            if (!playing) return position
            val p = position + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
            return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
        }
    }

    data class Call(
        val key: String,
        val name: String,
        val incoming: Boolean,
        /** Wall-clock start time when the call shows a running timer, else null. */
        val startedAt: Long?,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        val open: PendingIntent?,
        val icon: Bitmap?,
    )

    data class Timer(
        val key: String,
        val label: String,
        /** Countdown end, or stopwatch start (wall clock ms). Null when paused/frozen. */
        val time: Long?,
        val countDown: Boolean,
        /** Text to show when there's no running time (e.g. "Paused · 4:32"). */
        val text: String,
        val open: PendingIntent?,
    )

    data class Peek(
        val key: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val open: PendingIntent?,
        val postedAt: Long,
    )

    internal val _media = MutableStateFlow<Media?>(null)
    internal val _call = MutableStateFlow<Call?>(null)
    internal val _timer = MutableStateFlow<Timer?>(null)
    internal val _peek = MutableStateFlow<Peek?>(null)
    internal val _connected = MutableStateFlow(false)
    /** True while the overlay (accessibility) service is running. */
    internal val _overlay = MutableStateFlow(false)
    val overlay: StateFlow<Boolean> = _overlay.asStateFlow()

    val media: StateFlow<Media?> = _media.asStateFlow()
    val call: StateFlow<Call?> = _call.asStateFlow()
    val timer: StateFlow<Timer?> = _timer.asStateFlow()
    val peek: StateFlow<Peek?> = _peek.asStateFlow()
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun dismissPeek() {
        _peek.value = null
    }

    /** Sends a notification's PendingIntent from the foreground launcher (allowed to open activities). */
    fun send(context: Context, intent: PendingIntent?) {
        if (intent == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                intent.send()
            }
        }
    }
}

class IslandListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()
    private val alertedKeys = HashSet<String>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watchControllers(list.orEmpty())
    }

    override fun onListenerConnected() {
        IslandHub._connected.value = true
        val manager = getSystemService(MediaSessionManager::class.java)
        sessions = manager
        val me = ComponentName(this, IslandListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
            watchControllers(manager.getActiveSessions(me))
        }
        runCatching { activeNotifications?.forEach { readOngoing(it) } }
    }

    override fun onListenerDisconnected() {
        IslandHub._connected.value = false
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
        IslandHub._media.value = null
        IslandHub._call.value = null
        IslandHub._timer.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            readOngoing(sbn)
            maybePeek(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        alertedKeys.remove(sbn.key)
        if (IslandHub._call.value?.key == sbn.key) IslandHub._call.value = null
        if (IslandHub._timer.value?.key == sbn.key) IslandHub._timer.value = null
        if (IslandHub._peek.value?.key == sbn.key) IslandHub._peek.value = null
    }

    // ---------- Calls and timers (ongoing notifications) ----------

    private fun readOngoing(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)

        if (n.category == Notification.CATEGORY_CALL) {
            // CallStyle (Android 12+) puts the call type and buttons in extras.
            val type = extras.getInt("android.callType", 0) // 1 incoming, 2 ongoing, 3 screening
            val actions = n.actions.orEmpty()
            fun action(vararg words: String) = actions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                words.any { t.contains(it) }
            }?.actionIntent
            val incoming = type == 1 || (type == 0 && !chrono && action("answer", "accept") != null)
            IslandHub._call.value = IslandHub.Call(
                key = sbn.key,
                name = title.ifBlank { "Call" },
                incoming = incoming,
                startedAt = if (chrono && !incoming) n.`when` else null,
                answer = pending(extras, "android.answerIntent") ?: action("answer", "accept"),
                decline = pending(extras, "android.declineIntent") ?: action("decline", "reject"),
                hangUp = pending(extras, "android.hangUpIntent") ?: action("hang up", "end"),
                open = n.contentIntent,
                icon = largeIcon(n),
            )
            return
        }

        val clockApp = sbn.packageName.contains("deskclock") || sbn.packageName.contains("clock")
        if (chrono) {
            // Standard chronometer notification: `when` is the end (countdown) or the start (stopwatch)
            if (countDown || (clockApp && sbn.isOngoing)) {
                IslandHub._timer.value = IslandHub.Timer(
                    key = sbn.key,
                    label = title.ifBlank { if (countDown) "Timer" else "Stopwatch" },
                    time = n.`when`,
                    countDown = countDown,
                    text = text.ifBlank { title },
                    open = n.contentIntent,
                )
            }
            return
        }
        if (!sbn.isOngoing) return

        // Google Clock draws its timers and stopwatch in its own layout, so build that layout here
        // and read the running clock out of it.
        val custom = readCustomChronometer(n)
        if (custom != null) {
            val (chronometer, texts) = custom
            val all = (texts + title + text).joinToString(" ").lowercase()
            // A paused timer offers "Resume"/"Start" instead of "Pause"
            val buttons = n.actions.orEmpty().map { it.title?.toString()?.lowercase().orEmpty() }
            val paused = all.contains("paused") ||
                (buttons.any { it.contains("resume") || it == "start" } && buttons.none { it.contains("pause") })
            val countdown = chronometer.isCountDown
            // Chronometer.base is on the elapsedRealtime clock; turn it into wall-clock time
            val wall = System.currentTimeMillis() + (chronometer.base - SystemClock.elapsedRealtime())
            IslandHub._timer.value = IslandHub.Timer(
                key = sbn.key,
                label = title.ifBlank { texts.firstOrNull { it.isNotBlank() && !it.contains(':') } ?: if (countdown) "Timer" else "Stopwatch" },
                time = if (paused) null else wall,
                countDown = countdown,
                text = chronometer.text?.toString().orEmpty().ifBlank { "Paused" }.let { if (paused) "Paused · $it" else it },
                open = n.contentIntent,
            )
            return
        }

        // A clock notification without a running clock (e.g. "Time's up")
        if (clockApp && (n.category == Notification.CATEGORY_ALARM || n.category == "stopwatch" ||
                title.contains("timer", true) || title.contains("time's up", true))
        ) {
            IslandHub._timer.value = IslandHub.Timer(
                key = sbn.key,
                label = title.ifBlank { "Timer" },
                time = null,
                countDown = true,
                text = title.ifBlank { text },
                open = n.contentIntent,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readCustomChronometer(n: Notification): Pair<android.widget.Chronometer, List<String>>? {
        val views = listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView)
        if (views.isEmpty()) return null
        for (rv in views) {
            val parent = android.widget.FrameLayout(this)
            val inflated = runCatching { rv.apply(this, parent) }.getOrNull() ?: continue
            var found: android.widget.Chronometer? = null
            val texts = mutableListOf<String>()
            fun walk(v: android.view.View) {
                when (v) {
                    is android.widget.Chronometer -> if (found == null) found = v
                    is android.widget.TextView -> texts += v.text?.toString().orEmpty()
                    is android.view.ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(inflated)
            found?.let { return it to texts }
        }
        return null
    }

    // ---------- Notification peeks ----------

    private fun maybePeek(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return
        // Updates of something already shown that asked not to alert again
        val seen = !alertedKeys.add(sbn.key)
        if (seen && (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0) return

        val ranking = Ranking()
        val ranked = runCatching { currentRanking.getRanking(sbn.key, ranking) }.getOrDefault(false)
        if (ranked && (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT || !ranking.matchesInterruptionFilter())) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            .orEmpty()
        val pm = packageManager
        val appName = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val icon = largeIcon(n) ?: runCatching { pm.getApplicationIcon(sbn.packageName).islandBitmap(96) }.getOrNull()

        IslandHub._peek.value = IslandHub.Peek(
            key = sbn.key,
            appName = appName,
            title = title,
            text = text.replace('\n', ' '),
            icon = icon,
            open = n.contentIntent,
            postedAt = System.currentTimeMillis(),
        )
    }

    // ---------- Media sessions ----------

    private fun watchControllers(list: List<MediaController>) {
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
                override fun onSessionDestroyed() = refreshMedia()
            }
            runCatching { c.registerCallback(cb, main) }
            watched[c] = cb
        }
        refreshMedia()
    }

    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    private fun refreshMedia() {
        val controllers = watched.keys.filter { it.metadata != null }
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { c -> c.playbackState?.state?.let { it in PAUSED_STATES } == true }
        if (c == null) {
            IslandHub._media.value = null
            return
        }
        val md = c.metadata!!
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val artKey = "${c.packageName}|$title|$artist"
        val art = if (artKey == lastArtKey) lastArt else {
            (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
                ?.let { scaleDown(it, 256) }
                .also { lastArtKey = artKey; lastArt = it }
        }
        IslandHub._media.value = IslandHub.Media(
            title = title,
            artist = artist,
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            position = st?.position ?: 0L,
            positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            packageName = c.packageName,
            controller = c,
        )
    }

    // ---------- Helpers ----------

    @Suppress("DEPRECATION")
    private fun pending(extras: android.os.Bundle, key: String): PendingIntent? =
        runCatching { extras.getParcelable<PendingIntent>(key) }.getOrNull()

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.islandBitmap(128)
    }.getOrNull()

    companion object {
        private val PAUSED_STATES = setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)
    }
}

private fun scaleDown(b: Bitmap, max: Int): Bitmap {
    val big = maxOf(b.width, b.height)
    if (big <= max) return b
    val f = max.toFloat() / big
    return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
}

internal fun Drawable.islandBitmap(size: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return scaleDown(bitmap, size)
    val w = if (intrinsicWidth > 0) intrinsicWidth.coerceAtMost(size) else size
    val h = if (intrinsicHeight > 0) intrinsicHeight.coerceAtMost(size) else size
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
