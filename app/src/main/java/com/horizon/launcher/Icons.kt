package com.horizon.launcher

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
val LabelShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1.5f), 6f)

data class MenuAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

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
    icon: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
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
                .onGloballyPositioned { origin = it.positionInRoot() }
                .launcherGestures(
                    drag = drag,
                    onTap = { tap() },
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
                            size = DpSize(IconSize, IconSize),
                        ) { iconContent() }
                        started()
                    },
                    onDrag = { drag.move(it) },
                    onDragEnd = { dropped() },
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(IconSize), contentAlignment = Alignment.Center) { icon() }
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
        ItemMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = menu)
    }
}

@Composable
fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
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
fun AppImage(app: AppEntry, size: Dp = IconSize) {
    Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(size))
}

/** 2x2 preview of the first four apps in a folder. */
@Composable
fun FolderPreview(apps: List<AppEntry>) {
    Box(
        Modifier
            .size(IconSize)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.24f))
            .padding(7.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            apps.take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { AppImage(it, 19.dp) }
                }
            }
        }
    }
}
