package com.horizon.launcher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val Up = Color(0xFF4CC38A)
private val Down = Color(0xFFFF6B6B)

@Composable
private fun Card(title: String, icon: ImageVector, onTitle: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalInk.current.copy(alpha = 0.06f))
            .padding(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ---------- Now Playing ----------

/** What's playing, with play/pause, skip and a progress bar. Hidden when nothing is playing. */
@Composable
fun NowPlayingCard() {
    val context = LocalContext.current
    val media by IslandHub.media.collectAsState()
    val m = media ?: return
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(m.playing) { while (m.playing) { delay(1000); tick++ } }
    val appName = remember(m.packageName) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(m.packageName, 0)).toString() }
            .getOrDefault("Now playing")
    }
    val controls = m.controller.transportControls
    Card("Now playing · $appName", Icons.Outlined.MusicNote, onTitle = {
        context.packageManager.getLaunchIntentForPackage(m.packageName)?.let { context.startSafely(it) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(LocalInk.current.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                val art = m.art
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                else Icon(Icons.Outlined.MusicNote, null, tint = LocalInk.current.copy(alpha = 0.5f))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, color = LocalInk.current, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.artist.isNotBlank()) {
                    Text(m.artist, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (m.duration > 0) {
            val pos = if (tick >= 0) m.positionNow() else 0L
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (pos.toFloat() / m.duration).coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = LocalInk.current.copy(alpha = 0.12f),
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MediaButton(Icons.Filled.SkipPrevious, "Previous", small = true) { runCatching { controls.skipToPrevious() } }
            MediaButton(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (m.playing) "Pause" else "Play") {
                runCatching { if (m.playing) controls.pause() else controls.play() }
            }
            MediaButton(Icons.Filled.SkipNext, "Next", small = true) { runCatching { controls.skipToNext() } }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, small: Boolean = false, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(if (small) 44.dp else 56.dp)
            .clip(CircleShape)
            .background(if (small) Color.Transparent else primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (small) LocalInk.current else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(if (small) 28.dp else 30.dp))
    }
}

// ---------- Scores ----------

@Composable
fun SportsCard(vm: LauncherViewModel) {
    val context = LocalContext.current
    val games by vm.games.collectAsState()
    val settings by vm.settings.collectAsState()
    if (settings.sportsTeams.isEmpty()) return
    // Live games update every minute while you look.
    LaunchedEffect(Unit) { while (true) { vm.refreshSports(); delay(60_000) } }
    Card("Your teams", Icons.Outlined.SportsScore) {
        if (games.isEmpty()) {
            Text("Getting scores…", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            games.forEach { g -> GameRow(g) { g.link?.let { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }
        }
    }
}

@Composable
private fun GameRow(g: Game, onClick: () -> Unit) {
    val (away, home) = if (g.us.home) g.them to g.us else g.us to g.them
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TeamLine(away, g.state != "pre", g.state == "post" && away.winner)
            TeamLine(home, g.state != "pre", g.state == "post" && home.winner)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (g.live) {
                Text(
                    "LIVE",
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Down).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(g.detail, color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp)
            Text(League.of(g.team.league)?.label.orEmpty(), color = LocalInk.current.copy(alpha = 0.4f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun TeamLine(side: GameSide, showScore: Boolean, won: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val logo = side.logo
        if (logo != null) {
            WebPicture(logo, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            side.name, color = LocalInk.current, fontSize = 15.sp,
            fontWeight = if (won) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (showScore && side.score != null) {
            Text(side.score, color = LocalInk.current, fontSize = 16.sp, fontWeight = if (won) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

// ---------- Stocks ----------

@Composable
fun StocksCard(vm: LauncherViewModel) {
    val context = LocalContext.current
    val quotes by vm.quotes.collectAsState()
    val settings by vm.settings.collectAsState()
    if (settings.stocks.isEmpty()) return
    LaunchedEffect(Unit) { vm.refreshStocks() }
    Card("Watchlist", Icons.Outlined.ShowChart) {
        if (quotes.isEmpty()) Text("Getting prices…", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            quotes.forEach { q ->
                val color = if (q.change >= 0) Up else Down
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/finance/quote/${Uri.encode(q.symbol)}")))
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(q.symbol, color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(q.name, color = LocalInk.current.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Sparkline(q.points, color, Modifier.width(70.dp).height(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
                        Text(money(q.price, q.currency), color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            (if (q.change >= 0) "+" else "") + "%.2f%%".format(Locale.getDefault(), q.changePct),
                            color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun money(v: Double, currency: String): String = runCatching {
    val f = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault())
    f.currency = java.util.Currency.getInstance(currency)
    f.format(v)
}.getOrDefault("%.2f".format(Locale.getDefault(), v))

@Composable
private fun Sparkline(points: List<Double>, color: Color, modifier: Modifier) {
    if (points.size < 2) { Spacer(modifier); return }
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / (points.size - 1)
            val y = size.height - ((v - min) / range * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 2.5.dp.toPx(), Offset(size.width, size.height - ((points.last() - min) / range * size.height).toFloat()))
    }
}

/** A small picture from the web (team logos). */
@Composable
fun WebPicture(url: String, modifier: Modifier) {
    val bmp by androidx.compose.runtime.produceState(NewsRepository.cachedImage(url), url) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NewsRepository.loadImage(url, 96) }
    }
    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = modifier) } ?: Spacer(modifier)
}
