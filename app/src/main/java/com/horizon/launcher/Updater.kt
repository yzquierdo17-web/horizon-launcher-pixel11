package com.horizon.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer Horizon build waiting on GitHub. */
data class UpdateInfo(val build: Int, val url: String, val sizeBytes: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps Horizon up to date from its own GitHub releases: checks the latest build, downloads the
 * APK and hands it to Android's installer (you confirm once; your layout is kept because every
 * build is signed with the same permanent key).
 */
class Updater(private val context: Context) {

    /** The build number baked in by the build server (each build's version code). */
    fun currentBuild(): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** Network call. Null when offline or GitHub didn't answer. */
    fun latest(): UpdateInfo? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Horizon-Launcher")
        val body = try {
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val o = JSONObject(body)
        val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        UpdateInfo(
            build = build,
            url = apk.optString("browser_download_url"),
            sizeBytes = apk.optLong("size"),
            notes = cleanNotes(o.optString("body")),
        )
    }.getOrNull()

    /** True if Android lets Horizon install updates; otherwise opens the switch for it. */
    fun ensureInstallAllowed(): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(context, "Allow Horizon to install updates, then tap Install again", Toast.LENGTH_LONG).show()
        context.startSafely(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        )
        return false
    }

    /**
     * Streams the APK straight into an install session (no leftover files), reporting progress,
     * then asks Android to install it. Android shows its own "Update Horizon?" confirmation.
     */
    fun downloadAndInstall(info: UpdateInfo, onProgress: (Float) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (info.sizeBytes > 0) setSize(info.sizeBytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val c = URL(info.url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Horizon-Launcher")
            val total = if (info.sizeBytes > 0) info.sizeBytes else c.contentLengthLong
            c.inputStream.use { input ->
                session.openWrite("Horizon.apk", 0, if (total > 0) total else -1).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                    session.fsync(out)
                }
            }
            c.disconnect()
            val intent = Intent(context, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } catch (t: Throwable) {
            session.abandon()
            throw t
        } finally {
            session.close()
        }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/yzquierdo17-web/horizon-launcher-pixel11/releases/latest"

        /** Release notes come from the build's commit message; drop the sign-off lines. */
        fun cleanNotes(body: String): String = body.lines()
            .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") || it.startsWith("Newest build from main") }
            .joinToString("\n")
            .trim()
    }
}

/** Android's installer reports back here: ask for confirmation, or explain what went wrong. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Horizon restarts on the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
