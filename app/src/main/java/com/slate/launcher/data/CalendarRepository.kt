package com.slate.launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Instances
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Immutable
data class Meeting(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    /** Meet / Zoom / Teams / Webex link found in the event's location or notes. */
    val joinUrl: String?,
    val color: Int?,
)

/** Reads the next timed meeting from the phone's calendars (Google, Exchange, anything synced). */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Emits whenever any calendar data changes (new invite, edit, sync). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    /**
     * The meeting happening now, or the next one in the coming 12 hours.
     * Skips all-day events, declined invites and hidden calendars.
     */
    fun nextMeeting(now: Long = System.currentTimeMillis()): Meeting? {
        if (!hasPermission()) return null
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 12 * HOUR)
            ContentUris.appendId(it, now + 12 * HOUR)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.DISPLAY_COLOR,
        )
        val selection = "${Instances.ALL_DAY} = 0 AND ${Instances.END} > ? AND " +
            "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED} AND " +
            "${Instances.VISIBLE} = 1"
        return runCatching {
            context.contentResolver.query(uri, projection, selection, arrayOf(now.toString()), "${Instances.BEGIN} ASC")
                ?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val location = c.getString(4).orEmpty()
                    val notes = c.getString(5).orEmpty()
                    Meeting(
                        eventId = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Busy",
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        joinUrl = findJoinUrl(location) ?: findJoinUrl(notes),
                        color = if (c.isNull(6)) null else c.getInt(6),
                    )
                }
        }.getOrNull()
    }

    fun openEvent(m: Meeting) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, m.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, m.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, m.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the link; Meet, Zoom and Teams links open straight in their apps when installed. */
    fun join(m: Meeting) {
        val url = m.joinUrl ?: return openEvent(m)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        val JOIN_LINK = Regex(
            """https?://[^\s<>"')]*(meet\.google\.com|zoom\.us|teams\.microsoft\.com|teams\.live\.com|webex\.com|whereby\.com|gotomeet(ing)?\.(com|me)|chime\.aws)[^\s<>"')]*""",
            RegexOption.IGNORE_CASE,
        )
        fun findJoinUrl(text: String): String? = JOIN_LINK.find(text)?.value
    }
}
