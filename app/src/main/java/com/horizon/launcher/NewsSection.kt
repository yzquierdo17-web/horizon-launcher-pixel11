package com.horizon.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.withContext

/** What the news section remembers while the Today page is open. */
class NewsUiState {
    var filter by mutableStateOf<String?>(null)
    var showAll by mutableStateOf(false)
}

/** The "News" part of the Today page: topic chips, a lead story with a picture, then headlines. */
fun LazyListScope.newsSection(
    vm: LauncherViewModel,
    settings: LauncherSettings,
    stories: List<NewsStory>,
    loading: Boolean,
    ui: NewsUiState,
) {
    if (!settings.showNews) return

    item(key = "news-header") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Icon(Icons.Outlined.Newspaper, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("News", color = LocalInk.current, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    Icons.Outlined.Refresh,
                    "Refresh news",
                    tint = LocalInk.current.copy(alpha = 0.6f),
                    modifier = Modifier.clip(CircleShape).clickable { vm.refreshNews(force = true) }.padding(6.dp).size(20.dp),
                )
            }
        }
    }

    val topics = stories.map { it.topic }.distinct()
    if (topics.size > 1) item(key = "news-chips") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NewsChip("For you", ui.filter == null) { ui.filter = null; ui.showAll = false }
            topics.forEach { t -> NewsChip(t, ui.filter == t) { ui.filter = t; ui.showAll = false } }
        }
    }

    val shown = stories.filter { ui.filter == null || it.topic == ui.filter }
    if (shown.isEmpty()) {
        item(key = "news-empty") {
            Text(
                if (loading) "Getting today's headlines…" else "No headlines right now. Check your connection, then tap refresh.",
                color = LocalInk.current.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        return
    }

    val limit = if (ui.showAll) shown.size else minOf(shown.size, 8)
    val lead = shown.first()
    item(key = "news-lead-${lead.link}") {
        StoryCard(vm, lead, settings.newsImages, lead = true)
    }
    shown.subList(1, limit).forEach { s ->
        item(key = "news-${s.link}") { StoryCard(vm, s, settings.newsImages, lead = false) }
    }
    if (shown.size > limit) item(key = "news-more") {
        Text(
            "More stories",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(LocalInk.current.copy(alpha = 0.06f))
                .clickable { ui.showAll = true }
                .padding(vertical = 14.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NewsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else LocalInk.current.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) primary else LocalInk.current.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryCard(vm: LauncherViewModel, s: NewsStory, images: Boolean, lead: Boolean) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(LocalInk.current.copy(alpha = 0.06f))
                .combinedClickable(onClick = { openStory(context, s.link) }, onLongClick = { menu = true })
                .animateContentSize(),
        ) {
            if (lead && images && s.imageUrl != null) {
                NetImage(s.imageUrl, 600.dp, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    SourceLine(s)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.title,
                        color = LocalInk.current,
                        fontSize = if (lead) 19.sp else 16.sp,
                        lineHeight = if (lead) 25.sp else 21.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (lead) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!lead && images && s.imageUrl != null) {
                    Spacer(Modifier.width(12.dp))
                    NetImage(s.imageUrl, 160.dp, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menu = false; shareStory(context, s) },
            )
            DropdownMenuItem(
                text = { Text("Hide stories from ${s.source}") },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                onClick = { menu = false; vm.hideNewsSource(s.source) },
            )
        }
    }
}

@Composable
private fun SourceLine(s: NewsStory) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.sourceSite?.let { site ->
            NetImage(NewsRepository.faviconUrl(site), 32.dp, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(6.dp))
        }
        val ago = NewsRepository.ago(s.published)
        Text(
            if (ago.isEmpty()) s.source else "${s.source}  ·  $ago",
            color = LocalInk.current.copy(alpha = 0.6f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A picture from the web; shows nothing until it arrives (or if it can't load). */
@Composable
private fun NetImage(url: String, maxSize: Dp, modifier: Modifier) {
    val px = with(LocalDensity.current) { maxSize.roundToPx() }
    val bmp by produceState<Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { NewsRepository.loadImage(url, px) }
    }
    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier) }
}

private fun openStory(context: Context, link: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun shareStory(context: Context, s: NewsStory) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, s.title)
        .putExtra(Intent.EXTRA_TEXT, "${s.title}\n${s.link}")
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
