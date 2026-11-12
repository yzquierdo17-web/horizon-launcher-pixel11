package com.horizon.launcher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RESIZE_OPTIONS = listOf(2 to 1, 2 to 2, 3 to 2, 4 to 1, 4 to 2, 4 to 3, 5 to 2, 5 to 3)

@Composable
fun WidgetTile(
    item: HomeItem,
    vm: LauncherViewModel,
    drag: DragController,
    cellWidth: Dp,
    cellHeight: Dp,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val info = remember(item.widgetId) { vm.widgetManager.getAppWidgetInfo(item.widgetId) }
    var menuOpen by remember { mutableStateOf(false) }
    var resizeOpen by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentItem by rememberUpdatedState(item)
    val dropped by rememberUpdatedState(onDrop)
    val payload = DragPayload.FromHome(item.id)
    val hidden = drag.payload == payload

    Box(
        modifier
            .alpha(if (hidden) 0.25f else 1f)
            .padding(4.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .launcherGestures(
                drag = drag,
                onTap = null, // taps go to the widget itself
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
                onDragStart = { local ->
                    menuOpen = false
                    val it0 = currentItem
                    val size = DpSize(cellWidth * it0.spanX, cellHeight * it0.spanY)
                    drag.start(DragPayload.FromHome(it0.id), origin + local, size, it0.spanX, it0.spanY) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White.copy(alpha = 0.22f)),
                        )
                    }
                },
                onDrag = { drag.move(it) },
                onDragEnd = { dropped() },
            ),
    ) {
        if (info == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Widget unavailable", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
                val w = maxWidth.value
                val h = maxHeight.value
                AndroidView(
                    factory = { ctx ->
                        vm.widgetHost.createView(ctx, item.widgetId, info).apply { setPadding(0, 0, 0, 0) }
                    },
                    update = { view -> view.updateSizeCompat(w, h) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val canResize = info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
        ItemMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = listOfNotNull(
                if (canResize) MenuAction("Resize") { resizeOpen = true } else null,
                MenuAction("Remove") { vm.removeItem(item.id) },
            ),
        )
        ItemMenu(
            expanded = resizeOpen,
            onDismiss = { resizeOpen = false },
            actions = RESIZE_OPTIONS.map { (sx, sy) ->
                MenuAction("$sx × $sy", enabled = !(sx == item.spanX && sy == item.spanY)) {
                    vm.resizeWidget(item.id, sx, sy)
                }
            },
        )
    }
}

private fun AppWidgetHostView.updateSizeCompat(widthDp: Float, heightDp: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
    } else {
        @Suppress("DEPRECATION")
        updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
    }
}

@Composable
fun WidgetPicker(
    vm: LauncherViewModel,
    apps: List<AppEntry>,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
) {
    val providers = remember {
        runCatching { vm.widgetManager.installedProviders }.getOrDefault(emptyList())
    }
    val groups = remember(providers, apps) {
        providers
            .groupBy { it.provider.packageName }
            .map { (pkg, list) -> (apps.firstOrNull { it.packageName == pkg }?.label ?: pkg) to list }
            .sortedBy { it.first.lowercase() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF70D1016))
            .swallowTouches()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Widgets",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        if (groups.isEmpty()) {
            Text(
                "No widgets found",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            groups.forEach { (appLabel, list) ->
                item(key = "h-$appLabel") {
                    Text(
                        appLabel,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
                    )
                }
                items(list, key = { it.provider.flattenToString() + it.profile.hashCode() }) { info ->
                    WidgetRow(vm, info) { onPick(info) }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(vm: LauncherViewModel, info: AppWidgetProviderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
                val scale = minOf(1f, 480f / maxOf(w, h))
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    val label = remember(info) { runCatching { info.loadLabel(context.packageManager) }.getOrNull() ?: "Widget" }
    val (sx, sy) = vm.spansFor(info)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Text("$sx × $sy", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
