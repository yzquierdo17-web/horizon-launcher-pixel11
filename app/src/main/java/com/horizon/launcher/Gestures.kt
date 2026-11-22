package com.horizon.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/** Things a home-screen gesture can do. */
enum class GestureAction(val id: String, val label: String) {
    None("none", "Nothing"),
    Notifications("notifications", "Open notifications"),
    QuickSettings("quicksettings", "Open quick settings"),
    Drawer("drawer", "Open app drawer"),
    Search("search", "Search"),
    Lock("lock", "Lock screen"),
    Today("today", "Open Today"),
    Radar("radar", "Flight radar"),
    Settings("settings", "Horizon settings"),
    Wheel("wheel", "Add menu"),
    Recents("recents", "Recent apps"),
    Screenshot("screenshot", "Take a screenshot"),
    Flashlight("flashlight", "Flashlight"),
    Camera("camera", "Camera"),
    Assistant("assistant", "Assistant");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** The gestures you can set, with their setting names. */
enum class HomeGesture(val label: String) {
    SwipeDown("Swipe down"),
    DoubleTap("Double-tap"),
    Pinch("Pinch in"),
    TwoDown("Two-finger swipe down"),
    TwoUp("Two-finger swipe up");

    fun current(s: LauncherSettings) = when (this) {
        SwipeDown -> s.gestureSwipeDown
        DoubleTap -> s.gestureDoubleTap
        Pinch -> s.gesturePinch
        TwoDown -> s.gestureTwoDown
        TwoUp -> s.gestureTwoUp
    }

    fun set(s: LauncherSettings, value: String) = when (this) {
        SwipeDown -> s.copy(gestureSwipeDown = value)
        DoubleTap -> s.copy(gestureDoubleTap = value)
        Pinch -> s.copy(gesturePinch = value)
        TwoDown -> s.copy(gestureTwoDown = value)
        TwoUp -> s.copy(gestureTwoUp = value)
    }
}

/** What a gesture's setting says, in words ("Open notifications", "Open Spotify"). */
fun gestureLabel(id: String, apps: List<AppEntry>): String {
    if (id.startsWith("app:")) {
        val key = id.removePrefix("app:")
        return apps.firstOrNull { it.key == key }?.let { "Open ${it.label}" } ?: "Open an app"
    }
    return GestureAction.of(id)?.label ?: "Nothing"
}

/** Runs a gesture's action. */
fun runGesture(context: Context, vm: LauncherViewModel, id: String) {
    if (id.startsWith("app:")) {
        val key = id.removePrefix("app:")
        vm.apps.value.firstOrNull { it.key == key }?.let { vm.launch(it) }
        return
    }
    when (GestureAction.of(id)) {
        null, GestureAction.None -> Unit
        GestureAction.Notifications -> if (!IslandOverlayService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
            statusBar(context, "expandNotificationsPanel")
        }
        GestureAction.QuickSettings -> if (!IslandOverlayService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            statusBar(context, "expandSettingsPanel")
        }
        GestureAction.Drawer -> vm.openDrawer()
        GestureAction.Search -> vm.openDrawer(focusSearch = true)
        GestureAction.Lock -> if (!IslandOverlayService.lockScreen()) needsHelper(context, vm, "lock the screen")
        GestureAction.Today -> vm.openToday()
        GestureAction.Radar -> vm.showOverlay(LauncherViewModel.Overlay.Radar)
        GestureAction.Settings -> vm.openSettings(true)
        GestureAction.Wheel -> vm.showOverlay(LauncherViewModel.Overlay.Wheel)
        GestureAction.Recents -> if (!IslandOverlayService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) {
            needsHelper(context, vm, "open recent apps")
        }
        GestureAction.Screenshot -> if (!IslandOverlayService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            needsHelper(context, vm, "take screenshots")
        }
        GestureAction.Flashlight -> Torch.toggle(context)
        GestureAction.Camera -> context.startSafely(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        GestureAction.Assistant -> if (!context.startSafely(Intent(Intent.ACTION_VOICE_COMMAND))) {
            context.startSafely(Intent(Intent.ACTION_ASSIST))
        }
    }
}

private fun needsHelper(context: Context, vm: LauncherViewModel, what: String) {
    vm.toast("Turn on Horizon in Accessibility to $what with a gesture")
    context.startSafely(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

@SuppressLint("WrongConstant")
private fun statusBar(context: Context, method: String) {
    runCatching {
        val service = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod(method).invoke(service)
    }
}

/** Turns the flashlight on and off. */
object Torch {
    @Volatile private var on = false
    private var registered = false

    fun toggle(context: Context) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        runCatching {
            if (!registered) {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { on = enabled }
                }, null)
                registered = true
            }
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, !on)
        }
    }
}

/**
 * Two-finger gestures on the home screen: pinch in, and two-finger swipe up or down.
 * Once a second finger lands, the touches are kept from the one-finger swipes underneath.
 */
fun Modifier.twoFingerGestures(onPinch: () -> Unit, onTwoDown: () -> Unit, onTwoUp: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startSpan = 0f
            var startY = 0f
            var span = 0f
            var y = 0f
            var two = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.isEmpty()) break
                if (down.size >= 2) {
                    val a = down[0].position
                    val b = down[1].position
                    val s = hypot(a.x - b.x, a.y - b.y)
                    val cy = (a.y + b.y) / 2f
                    if (!two) {
                        two = true
                        startSpan = s
                        startY = cy
                    }
                    span = s
                    y = cy
                    event.changes.forEach { it.consume() }
                } else if (two) {
                    event.changes.forEach { it.consume() }
                }
            }
            if (two) {
                val dy = y - startY
                val ratio = if (startSpan > 0f) span / startSpan else 1f
                when {
                    ratio < 0.7f -> onPinch()
                    dy > 160f -> onTwoDown()
                    dy < -160f -> onTwoUp()
                }
            }
        }
    }
