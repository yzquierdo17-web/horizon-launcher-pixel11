package com.slate.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Immutable
data class AppEntry(
    /** Stable id: "package/activity#userSerial". Survives reboots, unique across profiles. */
    val key: String,
    val label: String,
    val searchKey: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWorkProfile: Boolean,
    val isSystemApp: Boolean,
    val icon: ImageBitmap,
    /** Android 13+ monochrome layer, used for Material You themed icons. */
    val monochrome: ImageBitmap?,
    /** Work profile is paused (quiet mode): shown greyed out, tapping offers to turn work apps on. */
    val isPaused: Boolean = false,
)

/** The managed (work) profile, if this phone has one. */
@Immutable
data class WorkProfile(val user: UserHandle, val paused: Boolean)

@Immutable
data class ShortcutEntry(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

/**
 * Source of truth for installed apps. Uses LauncherApps so work-profile apps,
 * badges and deep shortcuts all work the way they do in the Pixel Launcher.
 */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val iconPx = (72 * context.resources.displayMetrics.density).roundToInt()

    private val cache = ConcurrentHashMap<String, AppEntry>()
    private val refreshMutex = Mutex()

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val _workProfile = MutableStateFlow<WorkProfile?>(null)
    val workProfile: StateFlow<WorkProfile?> = _workProfile.asStateFlow()

    /** Work profile turned on/off, added or removed. */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            invalidate(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            invalidate(*packageNames)
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        ContextCompat.registerReceiver(context, profileReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        runCatching { context.unregisterReceiver(profileReceiver) }
    }

    fun refresh() {
        scope.launch(Dispatchers.Default) { refreshMutex.withLock { load() } }
    }

    private fun invalidate(vararg packages: String) {
        cache.keys.removeIf { key -> packages.any { key.startsWith("$it/") } }
        refresh()
    }

    private fun load() {
        val collator = Collator.getInstance()
        val me = Process.myUserHandle()
        val profiles = userManager.userProfiles
        val workUser = profiles.firstOrNull { it != me }
        _workProfile.value = workUser?.let { WorkProfile(it, userManager.isQuietModeEnabled(it)) }
        val entries = profiles.flatMap { user ->
            val paused = user != me && userManager.isQuietModeEnabled(user)
            launcherApps.getActivityList(null, user).mapNotNull { info ->
                if (info.componentName.packageName == context.packageName) return@mapNotNull null
                val key = keyFor(info.componentName, user)
                val entry = cache.getOrPut(key) { buildEntry(info, user, key, isWork = user != me) }
                if (entry.isPaused == paused) entry else entry.copy(isPaused = paused)
            }
        }.sortedWith(compareBy(collator) { it.label })
        _apps.value = entries
    }

    private fun keyFor(component: ComponentName, user: UserHandle) =
        "${component.flattenToString()}#${userManager.getSerialNumberForUser(user)}"

    private fun buildEntry(info: LauncherActivityInfo, user: UserHandle, key: String, isWork: Boolean): AppEntry {
        val badged = info.getBadgedIcon(densityDpi)
        val mono = if (Build.VERSION.SDK_INT >= 33) {
            (info.getIcon(densityDpi) as? AdaptiveIconDrawable)?.monochrome
        } else null
        val label = info.label.toString()
        return AppEntry(
            key = key,
            label = label,
            searchKey = label.normalizeForSearch(),
            packageName = info.componentName.packageName,
            component = info.componentName,
            user = user,
            isWorkProfile = isWork,
            isSystemApp = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            icon = badged.toBitmap(iconPx, iconPx).asImageBitmap(),
            monochrome = mono?.let { renderMonochrome(it, iconPx).asImageBitmap() },
        )
    }

    /** The monochrome layer is 108dp with a 72dp safe zone; crop to the safe zone. */
    private fun renderMonochrome(drawable: Drawable, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val inset = px / 4
        drawable.setBounds(-inset, -inset, px + inset, px + inset)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    // ---- Shortcuts (only available once Slate is the default Home app) ----

    fun shortcutsFor(app: AppEntry): List<ShortcutEntry> {
        val allowed = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
        if (!allowed) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
            )
        val list = runCatching { launcherApps.getShortcuts(query, app.user) }.getOrNull().orEmpty()
        return list
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo> { if (it.isDeclaredInManifest) 0 else 1 }.thenBy { it.rank })
            .take(4)
            .map { s ->
                ShortcutEntry(
                    info = s,
                    label = (s.shortLabel ?: s.longLabel ?: "").toString(),
                    icon = runCatching {
                        launcherApps.getShortcutIconDrawable(s, densityDpi)
                            ?.toBitmap(iconPx / 2, iconPx / 2)?.asImageBitmap()
                    }.getOrNull(),
                )
            }
    }

    // ---- Actions ----

    /**
     * Pause or resume all work apps. Android only allows this for the default Home app
     * while it's in the foreground. Returns false if the system refused.
     */
    fun setWorkPaused(paused: Boolean): Boolean {
        val work = _workProfile.value ?: return false
        // false (no exception) means Android is asking for the work PIN first: that's fine.
        val ok = runCatching { userManager.requestQuietModeEnabled(paused, work.user) }
            .getOrElse {
                toast("Set Slate as your Home app to pause work apps")
                false
            }
        refresh()
        return ok
    }

    fun launch(app: AppEntry, bounds: Rect?, options: Bundle?) {
        if (app.isPaused) {
            // Same behaviour as Pixel: tapping a paused work app offers to turn work apps back on.
            setWorkPaused(false)
            return
        }
        runCatching { launcherApps.startMainActivity(app.component, app.user, bounds, options) }
            .onFailure { toast("Couldn't open ${app.label}") }
    }

    fun launchShortcut(shortcut: ShortcutEntry, bounds: Rect?, options: Bundle?) {
        runCatching { launcherApps.startShortcut(shortcut.info, bounds, options) }
            .onFailure { toast("Shortcut unavailable") }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Lowercase and strip accents so "cafe" finds "Café". */
fun String.normalizeForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
