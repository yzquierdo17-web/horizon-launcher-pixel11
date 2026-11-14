package com.slate.launcher.ui

import android.graphics.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slate.launcher.data.AppEntry
import com.slate.launcher.data.ShortcutEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class IconPlace { Home, Dock, Drawer }

/** Everything an icon can do, wired up once in LauncherRoot. */
@Stable
class AppActions(
    val launch: (AppEntry, Rect?) -> Unit,
    val loadShortcuts: (AppEntry) -> List<ShortcutEntry>,
    val launchShortcut: (ShortcutEntry, Rect?) -> Unit,
    val appInfo: (AppEntry) -> Unit,
    val uninstall: (AppEntry) -> Unit,
    val addToHome: (AppEntry) -> Unit,
    val removeFromHome: (AppEntry) -> Unit,
    val addToDock: (AppEntry) -> Unit,
    val removeFromDock: (AppEntry) -> Unit,
    val hide: (AppEntry) -> Unit,
)

private class Anchor { var coords: LayoutCoordinates? = null }

private fun Anchor.rect(): Rect? = coords?.takeIf { it.isAttached }?.boundsInWindow()?.let {
    Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    app: AppEntry,
    place: IconPlace,
    iconSize: Dp,
    themed: Boolean,
    showLabel: Boolean,
    darkText: Boolean,
    isOnHome: Boolean,
    isInDock: Boolean,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val anchor = remember { Anchor() }
    val scale by animateFloatAsState(
        targetValue = if (pressed || menuOpen) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "iconPress",
    )

    val onWallpaper = place != IconPlace.Drawer
    val labelColor = when {
        !onWallpaper -> MaterialTheme.colorScheme.onSurface
        darkText -> Color(0xFF1F1F1F)
        else -> Color.White
    }
    val labelShadow = if (onWallpaper && !darkText) Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f) else null

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "Open",
                    onLongClickLabel = "Options",
                    onClick = { actions.launch(app, anchor.rect()) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(vertical = 6.dp),
        ) {
            AppIconImage(
                app = app,
                size = iconSize,
                themed = themed,
                contentDescription = if (showLabel) null else app.label,
                modifier = Modifier
                    .onGloballyPositioned { anchor.coords = it }
                    .graphicsLayer { scaleX = scale; scaleY = scale },
            )
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    color = labelColor,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        shadow = labelShadow,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        AppMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            app = app,
            place = place,
            isOnHome = isOnHome,
            isInDock = isInDock,
            actions = actions,
            anchorRect = { anchor.rect() },
        )
    }
}

@Composable
fun AppIconImage(
    app: AppEntry,
    size: Dp,
    themed: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val mono = app.monochrome
    // Paused work apps: greyscale and faded, like Pixel.
    val pausedFilter = remember(app.isPaused) {
        if (app.isPaused) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    }
    val faded = if (app.isPaused) Modifier.alpha(0.5f) else Modifier
    if (themed && mono != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .then(faded)
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Image(
                bitmap = mono,
                contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Image(
            bitmap = app.icon,
            contentDescription = contentDescription,
            colorFilter = pausedFilter,
            filterQuality = FilterQuality.High,
            modifier = modifier.then(faded).size(size),
        )
    }
}

@Composable
private fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    app: AppEntry,
    place: IconPlace,
    isOnHome: Boolean,
    isInDock: Boolean,
    actions: AppActions,
    anchorRect: () -> Rect?,
) {
    val shortcuts by produceState(emptyList<ShortcutEntry>(), expanded, app.key) {
        value = if (expanded) withContext(Dispatchers.IO) { actions.loadShortcuts(app) } else emptyList()
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        shortcuts.forEach { s ->
            DropdownMenuItem(
                text = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    val icon = s.icon
                    if (icon != null) Image(icon, null, Modifier.size(24.dp).clip(CircleShape))
                    else Icon(Icons.Outlined.Star, null)
                },
                onClick = {
                    onDismiss()
                    actions.launchShortcut(s, anchorRect())
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))

        MenuItem("App info", Icons.Outlined.Info, onDismiss) { actions.appInfo(app) }
        if (place != IconPlace.Dock) {
            if (isOnHome) MenuItem("Remove from Home", Icons.Outlined.RemoveCircleOutline, onDismiss) { actions.removeFromHome(app) }
            else MenuItem("Add to Home", Icons.Outlined.AddCircleOutline, onDismiss) { actions.addToHome(app) }
        }
        if (isInDock) MenuItem("Remove from Dock", Icons.Outlined.RemoveCircleOutline, onDismiss) { actions.removeFromDock(app) }
        else MenuItem("Pin to Dock", Icons.Outlined.PushPin, onDismiss) { actions.addToDock(app) }
        if (place == IconPlace.Drawer) MenuItem("Hide app", Icons.Outlined.VisibilityOff, onDismiss) { actions.hide(app) }
        if (!app.isSystemApp) MenuItem("Uninstall", Icons.Outlined.Delete, onDismiss) { actions.uninstall(app) }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, dismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        onClick = { dismiss(); onClick() },
    )
}
