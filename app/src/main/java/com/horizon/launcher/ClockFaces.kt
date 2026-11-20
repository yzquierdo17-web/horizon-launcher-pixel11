package com.horizon.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every clock face Horizon can show at the top of the first Home page. */
enum class ClockFace(val id: String, val label: String, val blurb: String) {
    Large("large", "Large", "Big thin time with the date under it"),
    Compact("compact", "Compact", "Time and date on one line, more room for apps"),
    Dial("dial", "Dial", "Rotating minute and seconds scales"),
    DialMinimal("dial_min", "Dial · Minimal", "The dial without tick marks"),
    DialBold("dial_bold", "Dial · Bold", "Heavy hour and a solid accent pill"),
    DialOutline("dial_outline", "Dial · Outline", "Outlined hour, like a watch face"),
    Stacked("stacked", "Stacked", "Bold hours over thin minutes"),
    Words("words", "Words", "The time written out: “four forty-five”");

    val dialVariant: DialVariant? get() = DialVariant.of(id)

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Large
    }
}

private val FaceShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 10f)

/** Bold hours over thin accent minutes, with the date and weather to the side. */
@Composable
fun StackedClock(
    now: LocalDateTime,
    is24h: Boolean,
    weather: String?,
    onOpenClock: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val font = LocalAppFont.current
    val hour = if (is24h) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.clickable(onClick = onOpenClock)) {
            Text(
                hour.toString().padStart(2, '0'),
                color = Color.White,
                style = TextStyle(fontFamily = font, fontSize = 104.sp, lineHeight = 92.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-5).sp, shadow = FaceShadow),
            )
            Text(
                now.minute.toString().padStart(2, '0'),
                color = MaterialTheme.colorScheme.primary,
                style = TextStyle(fontFamily = font, fontSize = 104.sp, lineHeight = 92.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-5).sp, shadow = FaceShadow),
            )
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.padding(bottom = 14.dp).clickable(onClick = onOpenCalendar)) {
            SpacedCaps(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), 12.sp, FontWeight.Bold)
            SpacedCaps(now.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())), 11.sp, FontWeight.Medium, 0.8f)
            if (weather != null) SpacedCaps(weather, 11.sp, FontWeight.Medium, 0.7f)
        }
    }
}

/** "It's / four / forty-five" with the date underneath. */
@Composable
fun WordsClock(
    now: LocalDateTime,
    weather: String?,
    onOpenClock: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val font = LocalAppFont.current
    val h12 = (now.hour % 12).let { if (it == 0) 12 else it }
    Column {
        Column(Modifier.clickable(onClick = onOpenClock)) {
            Text("It's", color = Color.White.copy(alpha = 0.7f), style = TextStyle(fontFamily = font, fontSize = 26.sp, fontWeight = FontWeight.Light, shadow = FaceShadow))
            Text(numberWord(h12), color = Color.White, style = TextStyle(fontFamily = font, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp, shadow = FaceShadow))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(minuteWords(now.minute), color = MaterialTheme.colorScheme.primary, style = TextStyle(fontFamily = font, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, shadow = FaceShadow))
                Spacer(Modifier.width(8.dp))
                Text(if (now.hour < 12) "am" else "pm", color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontFamily = font, fontSize = 18.sp, shadow = FaceShadow), modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.clickable(onClick = onOpenCalendar)) {
            val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
            Text(
                if (weather != null) "$date   ·   $weather" else date,
                color = Color.White.copy(alpha = 0.85f),
                style = TextStyle(fontFamily = font, fontSize = 16.sp, fontWeight = FontWeight.Medium, shadow = FaceShadow),
            )
        }
    }
}

@Composable
private fun SpacedCaps(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, alpha: Float = 1f) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = alpha),
        style = TextStyle(fontFamily = LocalAppFont.current, fontSize = size, fontWeight = weight, letterSpacing = 2.2.sp, shadow = FaceShadow),
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private val ONES = listOf(
    "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
)
private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty")

fun numberWord(n: Int): String = when {
    n < 20 -> ONES[n]
    n % 10 == 0 -> TENS[n / 10]
    else -> TENS[n / 10] + "-" + ONES[n % 10]
}

/** 0 → "o'clock", 5 → "oh five", 45 → "forty-five". */
fun minuteWords(m: Int): String = when {
    m == 0 -> "o'clock"
    m < 10 -> "oh " + ONES[m]
    else -> numberWord(m)
}

/** Pick a clock face, with a live preview of each over a sample wallpaper. */
@Composable
fun ClockFacePicker(vm: LauncherViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = LocalDateTime.now()
        }
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val weather = vm.weather.collectAsState().value?.short
    val surface = LocalThemeSurface.current
    Column(
        Modifier
            .fillMaxSize()
            .swallowTouches()
            .background(surface.copy(alpha = 0.98f))
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Clock face", color = LocalInk.current, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("Shown on your first Home page", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Text(
                "Done",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        ) {
            items(ClockFace.entries.toList(), key = { it.id }) { face ->
                val selected = settings.clockFace == face.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else LocalInk.current.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(24.dp),
                        )
                        .clickable { vm.setClockFace(face) },
                ) {
                    // Sample "wallpaper" so the white clock reads the way it will on Home.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (face.dialVariant != null) 200.dp else 170.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF1B3A5C), Color(0xFF0E1A2B), Color(0xFF1F2E24))))
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        FacePreview(face, now, is24h, weather)
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(face.label, color = LocalInk.current, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(face.blurb, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                        if (selected) Text("In use", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FacePreview(face: ClockFace, now: LocalDateTime, is24h: Boolean, weather: String?) {
    val noop = {}
    val font = LocalAppFont.current
    val variant = face.dialVariant
    when {
        variant != null -> Box(
            Modifier
                .fillMaxWidth()
                .requiredHeight(292.dp)
                .graphicsLayer {
                    scaleX = 0.68f
                    scaleY = 0.68f
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        ) { DialClock(is24h = is24h, weather = weather, variant = variant, onOpenClock = noop, onOpenCalendar = noop) }
        face == ClockFace.Stacked -> Box(Modifier.graphicsLayer { scaleX = 0.72f; scaleY = 0.72f; transformOrigin = TransformOrigin(0f, 0.5f) }) {
            StackedClock(now, is24h, weather, noop, noop)
        }
        face == ClockFace.Words -> Box(Modifier.graphicsLayer { scaleX = 0.72f; scaleY = 0.72f; transformOrigin = TransformOrigin(0f, 0.5f) }) {
            WordsClock(now, weather, noop, noop)
        }
        else -> {
            val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm"))
            if (face == ClockFace.Compact) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time, color = Color.White, style = TextStyle(fontFamily = font, fontSize = 44.sp, letterSpacing = (-1).sp, shadow = FaceShadow))
                    Spacer(Modifier.width(14.dp))
                    Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Color.White.copy(alpha = 0.92f), style = TextStyle(fontFamily = font, fontSize = 17.sp, fontWeight = FontWeight.Medium, shadow = FaceShadow), modifier = Modifier.padding(bottom = 8.dp))
                }
            } else {
                Column {
                    Text(time, color = Color.White, style = TextStyle(fontFamily = font, fontSize = 80.sp, fontWeight = FontWeight.Light, letterSpacing = (-2.5).sp, shadow = FaceShadow))
                    Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = Color.White.copy(alpha = 0.92f), style = TextStyle(fontFamily = font, fontSize = 18.sp, fontWeight = FontWeight.Medium, shadow = FaceShadow))
                }
            }
        }
    }
}
