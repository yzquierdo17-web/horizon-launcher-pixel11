package com.horizon.launcher

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val IconSize = 56.dp
val LocalSettings = compositionLocalOf { LauncherSettings() }

@Composable
fun scaledIconSize(): Dp = IconSize * LocalSettings.current.iconScale
val LabelShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1.5f), 6f)

data class MenuAction(
    val label: String,
    val enabled: Boolean = true,
    /** App shortcut (shown first, with a bolt). */
    val isShortcut: Boolean = false,
    val onClick: () -> Unit,
)

/** Apps greyed out right now because Focus mode is on (empty when Focus is off). */
val LocalFocusKeys = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** Loads an app's shortcuts for its long-press menu; provided by the launcher screen. */
val LocalAppShortcuts = androidx.compose.runtime.staticCompositionLocalOf<(AppEntry) -> List<MenuAction>> { { emptyList() } }

/**
 * An icon that can be tapped, long-pressed for a menu, or long-pressed and dragged.
 */
@Composable
fun LauncherIcon(
    label: String?,
    drag: DragController,
    payload: DragPayload,
    onTap: () -> Unit,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
    onDragStarted: () -> Unit = {},
    onDrop: () -> Unit,
    labelShadow: Boolean = false,
    /** Set for app icons, so the menu can show the app's shortcuts. */
    app: AppEntry? = null,
    icon: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    // Shortcuts are only looked up when the menu opens, so scrolling the drawer stays fast.
    val loadShortcuts = LocalAppShortcuts.current
    val shortcuts by produceState(emptyList<MenuAction>(), menuOpen, app?.key) {
        value = if (menuOpen && app != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadShortcuts(app) }
        } else emptyList()
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val iconSize = scaledIconSize()
    val tap by rememberUpdatedState(onTap)
    val started by rememberUpdatedState(onDragStarted)
    val dropped by rememberUpdatedState(onDrop)
    val currentPayload by rememberUpdatedState(payload)
    val currentIcon by rememberUpdatedState(icon)
    val hidden = drag.payload == payload

    Box(modifier.alpha(if (hidden) 0f else 1f), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .onGloballyPositioned {
                    origin = it.positionInRoot()
                    bounds = it.boundsInRoot()
                }
                .launcherGestures(
                    drag = drag,
                    onTap = {
                        drag.lastTapBounds = bounds
                        tap()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onDragStart = { local ->
                        menuOpen = false
                        val iconContent = currentIcon
                        drag.start(
                            payload = currentPayload,
                            rootPosition = origin + local,
                            size = DpSize(iconSize, iconSize),
                        ) { iconContent() }
                        started()
                    },
                    onDrag = { drag.move(it) },
                    onDragEnd = { dropped() },
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { icon() }
            if (label != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 12.sp,
                        shadow = if (labelShadow) LabelShadow else null,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ItemMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = shortcuts + menu)
    }
}

@Composable
fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEachIndexed { i, action ->
            if (i > 0 && actions[i - 1].isShortcut && !action.isShortcut) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = if (action.isShortcut) {
                    { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Bolt, contentDescription = null) }
                } else null,
                enabled = action.enabled,
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

@Composable
fun AppImage(app: AppEntry, size: Dp = scaledIconSize()) {
    // Paused work apps and Focus-mode distractions: greyscale and faded, like Pixel.
    val dimmed = app.isPaused || app.key in LocalFocusKeys.current
    val filter = remember(dimmed) {
        if (dimmed) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) },
        ) else null
    }
    val settings = LocalSettings.current
    val badges by IslandHub.badges.collectAsState()
    val count = if (settings.notificationDots && !dimmed) badges[IslandHub.badgeKey(app.packageName, app.user)] ?: 0 else 0
    Box(Modifier.size(size)) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            colorFilter = filter,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = Modifier.size(size).then(if (dimmed) Modifier.alpha(0.5f) else Modifier),
        )
        if (count > 0) NotificationDot(count, settings.dotCounts, Modifier.align(Alignment.TopEnd))
    }
}

/** Pixel-style dot (or a small count) on an icon with unread notifications. */
@Composable
private fun NotificationDot(count: Int, showCount: Boolean, modifier: Modifier) {
    val fill = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    if (showCount) {
        Box(
            modifier
                .offset(x = 4.dp, y = (-4).dp)
                .height(18.dp)
                .widthIn(min = 18.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, Color(0xFF0D1016), CircleShape)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "99+" else count.toString(), color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Box(
            modifier
                .offset(x = 1.dp, y = (-1).dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, Color(0xFF0D1016), CircleShape),
        )
    }
}

/** 2x2 preview of the first four apps in a folder. */
@Composable
fun FolderPreview(apps: List<AppEntry>) {
    val size = scaledIconSize()
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.24f))
            .padding(7.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            apps.take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { AppImage(it, (size - 18.dp) / 2) }
                }
            }
        }
    }
}
