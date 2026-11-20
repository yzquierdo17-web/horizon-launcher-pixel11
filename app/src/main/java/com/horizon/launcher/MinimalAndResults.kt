package com.horizon.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 6f)

// ---------------------------------------------------------------------------------------------
// Minimal mode
// ---------------------------------------------------------------------------------------------

/** Your favourite apps as a clean text list, in place of the icon grid, pages and dock. */
@Composable
fun MinimalHome(
    vm: LauncherViewModel,
    favorites: List<AppEntry>,
    dockKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.Bottom) {
        favorites.forEach { app ->
            MinimalAppRow(app, vm, dockKeys, onLaunch, fontSize = 30.sp, onHome = true)
        }
        if (favorites.isEmpty()) {
            Text(
                "Long-press here and choose Favourites to pick your apps",
                color = LocalInk.current.copy(alpha = 0.7f),
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier
                .clip(CircleShape)
                .clickable { vm.openDrawer() }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("All apps", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 15.sp, style = TextStyle(fontFamily = LocalAppFont.current, shadow = TextShadow))
        }
    }
}

/** One app as a line of text. Tap to open; long-press for the same menu as icons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalAppRow(
    app: AppEntry,
    vm: LauncherViewModel,
    dockKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    fontSize: TextUnit,
    onHome: Boolean = false,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val dimmed = app.isPaused || app.key in LocalFocusKeys.current
    val badges by IslandHub.badges.collectAsState()
    val unread = settings.notificationDots && !dimmed && (badges[IslandHub.badgeKey(app.packageName, app.user)] ?: 0) > 0
    var menuOpen by remember { mutableStateOf(false) }
    val loadShortcuts = LocalAppShortcuts.current
    val shortcuts by produceState(emptyList<MenuAction>(), menuOpen) {
        value = if (menuOpen) withContext(Dispatchers.IO) { loadShortcuts(app) } else emptyList()
    }
    val inDock = app.key in dockKeys
    val onWorkTab = app.isWork || app.key in settings.workApps
    val favs = settings.minimalFavorites

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onLaunch(app) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(vertical = if (onHome) 5.dp else 6.dp, horizontal = 4.dp)
                .alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                app.label,
                color = LocalInk.current,
                style = TextStyle(fontFamily = LocalAppFont.current, 
                    fontSize = fontSize,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.3).sp,
                    shadow = if (onHome) TextShadow else null,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.isWork) {
                Spacer(Modifier.width(8.dp))
                Text("work", color = LocalInk.current.copy(alpha = 0.45f), fontSize = 12.sp)
            }
            if (unread) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        ItemMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = shortcuts + listOf(
                MenuAction(if (app.key in favs) "Remove from favourites" else "Add to favourites") {
                    vm.updateSettings { s ->
                        val base = s.minimalFavorites.ifEmpty { vm.defaultFavorites() }
                        s.copy(minimalFavorites = if (app.key in base) base - app.key else base + app.key)
                    }
                },
                MenuAction(if (inDock) "Remove from dock" else "Add to dock", enabled = inDock || dockKeys.size < MAX_DOCK) {
                    vm.toggleDock(app.key)
                },
                MenuAction(if (onWorkTab) "Remove from Work tab" else "Add to Work tab", enabled = !app.isWork) {
                    vm.setWorkApp(app.key, !onWorkTab)
                },
                MenuAction("Hide app") { vm.hideApp(app) },
                MenuAction("App info") { vm.openAppInfo(app) },
                MenuAction("Uninstall") {
                    context.startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                },
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Search results
// ---------------------------------------------------------------------------------------------

/** Math or unit answer at the top of search. Tap to copy it. */
@Composable
fun AnswerCard(query: String, answer: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Answer", answer.replace(",", "")))
                android.widget.Toast.makeText(context, "Copied $answer", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(query.trim(), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
            Text("= $answer", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Icon(Icons.Outlined.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ResultHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun ResultRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onDismiss: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(LocalInk.current.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = LocalInk.current, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalInk.current, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = LocalInk.current.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        if (onDismiss != null) {
            Icon(
                Icons.Filled.Close,
                "Not now",
                tint = LocalInk.current.copy(alpha = 0.6f),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(8.dp).size(18.dp),
            )
        }
    }
}

/** A person from Contacts: tap to open, or call / text straight from search. */
@Composable
fun ContactRow(c: ContactHit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { context.startSafely(Intent(Intent.ACTION_VIEW, c.lookupUri)) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.name.trim().take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = LocalInk.current, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.number != null) Text(c.number, color = LocalInk.current.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        if (c.number != null) {
            RoundAction(Icons.Outlined.Message, "Text") {
                context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))))
            }
            Spacer(Modifier.width(6.dp))
            RoundAction(Icons.Outlined.Call, "Call") {
                context.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number))))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(LocalInk.current.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = LocalInk.current, modifier = Modifier.size(19.dp)) }
}

