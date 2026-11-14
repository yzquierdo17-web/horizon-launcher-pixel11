package com.slate.launcher.ui

import androidx.compose.foundation.clickable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.WorkOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slate.launcher.LauncherUiState
import com.slate.launcher.data.AppEntry
import com.slate.launcher.data.normalizeForSearch
import com.slate.launcher.ui.theme.Dimens
import com.slate.launcher.util.SystemActions

@Composable
fun AppDrawer(
    state: LauncherUiState,
    drawer: AppDrawerState,
    actions: AppActions,
    requestFocus: Boolean,
    translucent: Boolean,
    onFocusConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetWorkPaused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    var query by rememberSaveable { mutableStateOf("") }
    // Opens on the Work tab during work hours, Personal otherwise. Search always covers both.
    var workTab by rememberSaveable(state.workTime) { mutableStateOf(state.workTime) }
    val showTabs = state.hasWorkProfile && query.isBlank()
    val tabApps = remember(state.drawerApps, showTabs, workTab) {
        if (!showTabs) state.drawerApps else state.drawerApps.filter { it.isWorkProfile == workTab }
    }
    val results = remember(query, tabApps) { searchApps(tabApps, query) }
    val suggested = remember(state.suggestedApps, showTabs, workTab) {
        if (!showTabs) state.suggestedApps else state.suggestedApps.filter { it.isWorkProfile == workTab }
    }
    val homeKeys = remember(state.homeApps) { state.homeApps.mapTo(HashSet()) { it.key } }
    val dockKeys = remember(state.dockApps) { state.dockApps.mapTo(HashSet()) { it.key } }

    val gridState = rememberLazyGridState()
    val connection = remember(drawer, gridState) { DrawerScrollConnection(drawer, gridState) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Tapping the Home search pill opens the drawer straight into typing.
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            onFocusConsumed()
        }
    }
    // Scrolling or closing dismisses the keyboard.
    LaunchedEffect(gridState.isScrollInProgress, drawer.target) {
        if (gridState.isScrollInProgress || drawer.target == 0f) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    // New query → back to the top of the list.
    LaunchedEffect(query) { if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0) }

    val showSuggestions = query.isBlank() && suggested.isNotEmpty() && !(workTab && state.workPaused)
    val headerCount = if (showSuggestions) 2 else 0
    val letterIndex = remember(results, query, headerCount) {
        if (query.isNotBlank()) emptyMap()
        else buildMap { results.forEachIndexed { i, app -> putIfAbsent(app.sectionLetter(), i + headerCount) } }
    }
    val scope = rememberCoroutineScope()
    val iconSize = Dimens.BaseIcon * s.iconScale

    Surface(
        // Slightly see-through so the blurred wallpaper glows through (when blur is available).
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (translucent) 0.80f else 0.97f),
        modifier = modifier.fillMaxSize().nestedScroll(connection),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
        ) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                focusRequester = focusRequester,
                onSubmit = { results.firstOrNull()?.let { actions.launch(it, null) } },
                onOpenSettings = onOpenSettings,
            )
            if (showTabs) {
                ProfileTabs(workTab = workTab, onSelect = { workTab = it })
                if (workTab && state.workPaused) PausedBanner(onTurnOn = { onSetWorkPaused(false) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val showScroller = letterIndex.size > 5
                LazyVerticalGrid(
                    columns = GridCells.Fixed(s.columns),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 8.dp, end = if (showScroller) 28.dp else 8.dp, top = 4.dp, bottom = 24.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (showSuggestions) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "suggested") {
                            Row(Modifier.fillMaxWidth()) {
                                suggested.forEach { app ->
                                    key(app.key) {
                                        AppIcon(
                                            app = app, place = IconPlace.Drawer, iconSize = iconSize,
                                            themed = s.themedIcons, showLabel = true, darkText = false,
                                            isOnHome = app.key in homeKeys, isInDock = app.key in dockKeys,
                                            actions = actions, modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                        )
                                    }
                                }
                                repeat(s.columns - suggested.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "divider") {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    items(results, key = { it.key }) { app ->
                        AppIcon(
                            app = app, place = IconPlace.Drawer, iconSize = iconSize, themed = s.themedIcons,
                            showLabel = true, darkText = false,
                            isOnHome = app.key in homeKeys, isInDock = app.key in dockKeys,
                            actions = actions,
                            modifier = Modifier.animateItem().padding(vertical = 4.dp),
                        )
                    }
                    if (query.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "web") { WebSuggestions(query) }
                    }
                }
                if (showTabs && workTab && !state.workPaused) {
                    Surface(
                        onClick = { onSetWorkPaused(true) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 3.dp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Icon(Icons.Outlined.WorkOff, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pause work apps", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (showScroller) {
                    FastScroller(
                        letters = letterIndex.keys.toList(),
                        onSelect = { letter ->
                            letterIndex[letter]?.let { scope.launch { gridState.scrollToItem(it) } }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileTabs(workTab: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        TabPill("Personal", Icons.Outlined.Person, selected = !workTab, Modifier.weight(1f)) { onSelect(false) }
        TabPill("Work", Icons.Outlined.Work, selected = workTab, Modifier.weight(1f)) { onSelect(true) }
    }
}

@Composable
private fun TabPill(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = modifier.height(40.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PausedBanner(onTurnOn: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Icon(Icons.Outlined.WorkOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Work apps are paused", style = MaterialTheme.typography.titleSmall)
                Text("You won't get work notifications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onTurnOn) { Text("Turn on") }
        }
    }
}

/** A–Z rail: touch or slide to jump, with a letter bubble and a haptic tick per letter. */
@Composable
private fun FastScroller(letters: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var active by remember { mutableStateOf<String?>(null) }
    var heightPx by remember { mutableIntStateOf(1) }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun pick(y: Float) {
        val list = currentLetters
        val i = ((y / heightPx) * list.size).toInt().coerceIn(0, list.lastIndex)
        val letter = list[i]
        if (letter != active) {
            active = letter
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentOnSelect(letter)
        }
    }

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(24.dp)
                .clip(CircleShape)
                .background(
                    if (active != null) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                )
                .padding(vertical = 6.dp)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = null
                    }
                },
        ) {
            letters.forEach { letter ->
                Text(
                    letter,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (letter == active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        active?.let { letter ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-72).dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(letter, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

private fun AppEntry.sectionLetter(): String {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c.isLetter()) c.toString() else "#"
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp)) {
            Icon(Icons.Outlined.Search, null, tint = colors.primary)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
            } else {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Launcher settings") }
            }
        }
    }
}

@Composable
private fun WebSuggestions(query: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Suggestion(Icons.Outlined.Language, "Search the web for “$query”") { SystemActions.webSearch(context, query) }
        Suggestion(Icons.Outlined.ShoppingBag, "Search Play Store for “$query”") { SystemActions.playStoreSearch(context, query) }
    }
}

@Composable
private fun Suggestion(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    )
}

/**
 * Ranked search: label prefix > word prefix > initials ("gm" → Google Maps) > substring.
 * Alphabetical order is preserved within each rank.
 */
internal fun searchApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim().normalizeForSearch()
    if (q.isEmpty()) return apps
    return apps.mapNotNull { app ->
        val label = app.searchKey
        val words = label.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
        val rank = when {
            label.startsWith(q) -> 0
            words.any { it.startsWith(q) } -> 1
            q.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(q) -> 2
            label.contains(q) -> 3
            else -> return@mapNotNull null
        }
        rank to app
    }.sortedBy { it.first }.map { it.second }
}
