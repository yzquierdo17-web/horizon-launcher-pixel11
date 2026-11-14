package com.slate.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.slate.launcher.HomeWidget
import com.slate.launcher.data.SlateWidgetHostView
import com.slate.launcher.data.WidgetOption
import com.slate.launcher.data.WidgetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Callbacks for widgets on Home, wired in LauncherRoot. */
class WidgetActions(
    val resize: (id: Int, rows: Int) -> Unit,
    val move: (id: Int, up: Boolean) -> Unit,
    val remove: (id: Int) -> Unit,
)

fun widgetHeight(rows: Int): Dp = (WidgetRepository.ROW_HEIGHT_DP * rows + 8f * (rows - 1)).dp

/** Widgets stacked full-width under the clock. Long-press a widget to resize, reorder or remove. */
@Composable
fun WidgetStack(
    widgets: List<HomeWidget>,
    repo: WidgetRepository,
    actions: WidgetActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        widgets.forEachIndexed { index, widget ->
            key(widget.id) {
                HostedWidget(
                    widget = widget,
                    repo = repo,
                    canMoveUp = index > 0,
                    canMoveDown = index < widgets.lastIndex,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun HostedWidget(
    widget: HomeWidget,
    repo: WidgetRepository,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: WidgetActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(widgetHeight(widget.rows)),
    ) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        AndroidView(
            factory = { ctx ->
                (repo.host.createView(ctx, widget.id, widget.info) as SlateWidgetHostView).apply {
                    setPadding(0, 0, 0, 0)
                }
            },
            update = { view ->
                view.onLongPress = { menuOpen = true }
                view.updateSize(widthDp, heightDp)
            },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            listOf(1 to "Small", 2 to "Medium", 3 to "Large", 4 to "Extra large").forEach { (rows, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (rows == widget.rows) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { menuOpen = false; actions.resize(widget.id, rows) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (canMoveUp) DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                onClick = { menuOpen = false; actions.move(widget.id, true) },
            )
            if (canMoveDown) DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                onClick = { menuOpen = false; actions.move(widget.id, false) },
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menuOpen = false; actions.remove(widget.id) },
            )
        }
    }
}

/** Bottom sheet listing every widget on the phone, grouped by app, with live previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPickerSheet(
    repo: WidgetRepository,
    onPick: (WidgetOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups by produceState<List<Pair<String, List<WidgetOption>>>?>(null) {
        value = withContext(Dispatchers.IO) { repo.options().groupBy { it.appLabel }.toList() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text("Widgets", style = MaterialTheme.typography.headlineSmall)
        }
        val list = groups
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(200.dp))
        } else if (list.isEmpty()) {
            Text(
                "No widgets available.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
                items(list, key = { it.first }) { (app, options) ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            app,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(options, key = { it.info.provider.flattenToString() + it.info.profile.hashCode() }) { option ->
                                WidgetCard(repo, option, onClick = { onPick(option) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(repo: WidgetRepository, option: WidgetOption, onClick: () -> Unit) {
    val maxPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, option) {
        value = withContext(Dispatchers.IO) { repo.preview(option, maxPx) }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(width = 180.dp, height = 190.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                preview?.let {
                    Image(
                        it, null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 130.dp).fillMaxHeight(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
