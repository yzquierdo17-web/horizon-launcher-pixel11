package com.horizon.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the news feed remembers while the Today page is open. */
class NewsUiState {
    var filter by mutableStateOf<String?>(null)
    var limit by mutableIntStateOf(10)
}

/** The Discover-style feed: topic chips, then big picture cards that keep coming as you scroll. */
fun LazyListScope.newsSection(
    vm: LauncherViewModel,
    settings: LauncherSettings,
    stories: List<NewsStory>,
    loading: Boolean,
    ui: NewsUiState,
) {
    if (!settings.showNews) return

    item(key = "news-header") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text(
                "Discover",
                color = LocalInk.current,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(18.dp),
                )
            } else {
                Icon(
                    Icons.Outlined.Refresh, "Refresh",
                    tint = LocalInk.current.copy(alpha = 0.6f),
                    modifier = Modifier.clip(CircleShape).clickable { vm.refreshNews(force = true) }.padding(8.dp).size(20.dp),
                )
            }
            Icon(
                Icons.Outlined.Tune, "Your interests",
                tint = LocalInk.current.copy(alpha = 0.6f),
                modifier = Modifier.clip(CircleShape).clickable { vm.openSettings(true) }.padding(8.dp).size(20.dp),
            )
        }
    }

    val topics = stories.map { it.topic }.distinct()
    if (topics.size > 1) item(key = "news-chips") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NewsChip("For you", ui.filter == null) { ui.filter = null; ui.limit = 10 }
            topics.forEach { t -> NewsChip(t, ui.filter == t) { ui.filter = t; ui.limit = 10 } }
        }
    }

    val shown = stories.filter { ui.filter == null || it.topic == ui.filter }
    if (shown.isEmpty()) {
        item(key = "news-empty") {
            if (loading) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { repeat(2) { SkeletonCard() } }
            } else {
                Text(
                    "No stories right now. Check your connection, then pull down to refresh.",
                    color = LocalInk.current.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        return
    }

    val limit = minOf(ui.limit, shown.size)
    shown.subList(0, limit).forEach { s ->
        item(key = "news-${s.link}") {
            StoryCard(vm, s, settings.newsImages, s.link in settings.newsHearts)
        }
    }
    if (limit < shown.size) {
        // Reaching the end loads the next few, so the feed keeps going.
        item(key = "news-more-$limit") {
            LaunchedEffect(limit) {
                delay(120)
                ui.limit = limit + 10
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
    } else {
        item(key = "news-end") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text("You're all caught up", color = LocalInk.current, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Pull down for new stories",
                    color = LocalInk.current.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun NewsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        color = if (selected) primary else LocalInk.current.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else LocalInk.current.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A Discover card: big picture, headline, then the source with heart, share and more. */
@Composable
private fun StoryCard(vm: LauncherViewModel, s: NewsStory, images: Boolean, hearted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    var menu by remember { mutableStateOf(false) }
    val resolved by produceState(vm.newsRepo.cached(s), s.link) {
        if (value == null) value = vm.newsRepo.resolve(s)
    }
    val image = resolved?.image ?: s.imageUrl
    suspend fun realUrl() = resolved?.url ?: withTimeoutOrNull(2500) { vm.newsRepo.resolve(s) }?.url ?: s.link

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalInk.current.copy(alpha = 0.06f))
            .clickable { scope.launch { openStory(context, realUrl()) } }
            .animateContentSize(tween(220)),
    ) {
        if (images) {
            val picture = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp))
            when {
                image != null -> NetImage(image, 720.dp, picture, placeholder = true)
                resolved == null -> Box(picture.background(LocalInk.current.copy(alpha = 0.05f)))
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 4.dp)) {
            Text(
                s.title,
                color = LocalInk.current,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                s.sourceSite?.let { site ->
                    NetImage(NewsRepository.faviconUrl(site), 32.dp, Modifier.size(18.dp).clip(CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                val ago = NewsRepository.ago(s.published)
                Text(
                    if (ago.isEmpty()) s.source else "${s.source} · $ago",
                    color = LocalInk.current.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val pop = remember { Animatable(1f) }
                LaunchedEffect(hearted) {
                    if (hearted) {
                        pop.animateTo(1.3f, tween(120))
                        pop.animateTo(1f, tween(160))
                    }
                }
                Icon(
                    if (hearted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if (hearted) "Remove heart" else "Heart",
                    tint = if (hearted) primary else LocalInk.current.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { vm.toggleHeart(s) }
                        .padding(10.dp)
                        .size(20.dp)
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                )
                Icon(
                    Icons.Outlined.Share, "Share",
                    tint = LocalInk.current.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { scope.launch { shareStory(context, s.title, realUrl()) } }
                        .padding(10.dp)
                        .size(20.dp),
                )
                Box {
                    Icon(
                        Icons.Outlined.MoreVert, "More",
                        tint = LocalInk.current.copy(alpha = 0.6f),
                        modifier = Modifier.clip(CircleShape).clickable { menu = true }.padding(10.dp).size(20.dp),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Not interested in this story") },
                            leadingIcon = { Icon(Icons.Outlined.ThumbDown, null) },
                            onClick = { menu = false; vm.dismissStory(s) },
                        )
                        DropdownMenuItem(
                            text = { Text("Don't show stories from ${s.source}") },
                            leadingIcon = { Icon(Icons.Outlined.Block, null) },
                            onClick = { menu = false; vm.hideNewsSource(s.source) },
                        )
                        DropdownMenuItem(
                            text = { Text("Manage interests") },
                            leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                            onClick = { menu = false; vm.openSettings(true) },
                        )
                    }
                }
            }
        }
    }
}

/** Grey stand-in cards while the first stories load. */
@Composable
private fun SkeletonCard() {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ink.copy(alpha = 0.06f))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(ink.copy(alpha = 0.06f)))
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.08f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.08f)))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.06f)))
        }
    }
}

/** A picture from the web; fades in when it arrives. */
@Composable
private fun NetImage(url: String, maxSize: Dp, modifier: Modifier, placeholder: Boolean = false) {
    val px = with(LocalDensity.current) { maxSize.roundToPx() }
    val cached = remember(url) { NewsRepository.cachedImage(url) }
    val bmp by produceState(cached, url) {
        if (value == null) value = withContext(Dispatchers.IO) { NewsRepository.loadImage(url, px) }
    }
    val shown = bmp
    if (shown == null) {
        if (placeholder) Box(modifier.background(LocalInk.current.copy(alpha = 0.05f)))
        return
    }
    val alpha by animateFloatAsState(if (shown != null) 1f else 0f, tween(250), label = "fade")
    Image(
        shown.asImageBitmap(), null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { this.alpha = if (cached != null) 1f else alpha },
    )
}

/** Opens the article in an in-app browser tab, like Discover does. */
private fun openStory(context: Context, link: String) {
    val uri = Uri.parse(link)
    runCatching {
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
        if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, uri)
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun shareStory(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
