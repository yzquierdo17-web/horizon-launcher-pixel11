package com.horizon.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Icon outline used for every app, so the grid looks uniform. */
enum class IconShape(val id: String, val label: String) {
    System("system", "Phone default"),
    Circle("circle", "Circle"),
    Squircle("squircle", "Squircle"),
    RoundedSquare("rounded", "Rounded square"),
    Teardrop("teardrop", "Teardrop");

    fun path(size: Float): Path = when (this) {
        System, Circle -> Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        Squircle -> superellipse(size, 5.0)
        RoundedSquare -> Path().apply {
            addRoundRect(RectF(0f, 0f, size, size), size * 0.24f, size * 0.24f, Path.Direction.CW)
        }
        Teardrop -> Path().apply {
            val r = size / 2f
            val small = size * 0.12f
            // corners: top-left, top-right, bottom-right, bottom-left
            addRoundRect(RectF(0f, 0f, size, size), floatArrayOf(r, r, r, r, small, small, r, r), Path.Direction.CW)
        }
    }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: System
    }
}

private fun superellipse(size: Float, n: Double): Path {
    val r = size / 2.0
    val path = Path()
    val steps = 96
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = r + r * sign(c) * abs(c).pow(2.0 / n)
        val y = r + r * sign(s) * abs(s).pow(2.0 / n)
        if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
    }
    path.close()
    return path
}

/** An installed icon pack (any pack made for Nova, Lawnchair, ADW and most others). */
data class IconPackInfo(val packageName: String, val label: String)

fun installedIconPacks(ctx: Context): List<IconPackInfo> {
    val pm = ctx.packageManager
    val actions = listOf("com.novalauncher.THEME", "org.adw.launcher.THEMES", "com.gau.go.launcherex.theme", "app.lawnchair.icons.THEMED_ICON")
    return actions.flatMap { action ->
        runCatching { pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
    }
        .map { it.activityInfo.packageName }
        .distinct()
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            IconPackInfo(pkg, label)
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * Reads an icon pack's appfilter.xml: which drawable replaces which app, plus the optional
 * back plate / mask / overlay / scale it uses to restyle apps it doesn't cover.
 */
class IconPack private constructor(
    private val res: Resources,
    private val pkg: String,
    private val map: Map<String, String>,
    private val back: String?,
    private val mask: String?,
    private val upon: String?,
    private val scale: Float,
) {
    private fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    /** The pack's own icon for this app, if it has one. */
    fun iconFor(component: android.content.ComponentName): Drawable? {
        val name = map[component.flattenToString()] ?: map[component.packageName] ?: return null
        return drawable(name)
    }

    /** Restyle an icon the pack doesn't cover, using its back plate, mask and overlay. Null if the pack has none. */
    fun restyle(original: Bitmap, px: Int): Bitmap? {
        val backD = back?.let(::drawable) ?: return null
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        backD.setBounds(0, 0, px, px)
        backD.draw(c)
        val inner = (px * scale).toInt().coerceAtLeast(1)
        val off = (px - inner) / 2f
        val layer = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(Bitmap.createScaledBitmap(original, inner, inner, true), off, off, Paint(Paint.FILTER_BITMAP_FLAG))
        mask?.let(::drawable)?.let { m ->
            val mb = m.toBitmap(px, px)
            lc.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(layer, 0f, 0f, null)
        upon?.let(::drawable)?.let { u -> u.setBounds(0, 0, px, px); u.draw(c) }
        return out
    }

    companion object {
        fun load(ctx: Context, pkg: String): IconPack? = runCatching {
            val pm = ctx.packageManager
            val res = pm.getResourcesForApplication(pkg)
            val parser: XmlPullParser = res.getIdentifier("appfilter", "xml", pkg).takeIf { it != 0 }
                ?.let { res.getXml(it) }
                ?: XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(ctx.createPackageContext(pkg, 0).assets.open("appfilter.xml"), "UTF-8")
                }
            val map = HashMap<String, String>()
            var back: String? = null
            var mask: String? = null
            var upon: String? = null
            var scale = 1f
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component") ?: continue
                        val draw = parser.getAttributeValue(null, "drawable") ?: continue
                        // "ComponentInfo{pkg/cls}" → "pkg/cls"
                        val key = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                        if (key.isNotBlank()) map.putIfAbsent(key, draw)
                        key.substringBefore('/').takeIf { it.isNotBlank() }?.let { map.putIfAbsent(it, draw) }
                    }
                    "iconback" -> back = parser.getAttributeValue(null, "img1") ?: back
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1") ?: mask
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1") ?: upon
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
                }
            }
            IconPack(res, pkg, map, back, mask, upon, scale.coerceIn(0.3f, 1f))
        }.getOrNull()
    }
}

/**
 * Turns an app's icon into the bitmap Horizon shows, in priority order:
 * icon pack → themed (monochrome) → reshaped adaptive icon → the phone's own icon.
 * Work-profile badges are kept.
 */
class IconRenderer(
    private val ctx: Context,
    private val px: Int,
    private val shape: IconShape,
    private val themed: Boolean,
    private val pack: IconPack?,
) {
    private val me = Process.myUserHandle()
    private val clip = shape.path(px.toFloat())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(info: LauncherActivityInfo): Bitmap {
        val raw = runCatching { info.getIcon(0) }.getOrNull()
        val base: Bitmap =
            pack?.iconFor(info.componentName)?.toBitmap(px, px)
                ?: (if (themed && info.user == me) raw?.let { themedBitmap(it) } else null)
                ?: raw?.let { styled(it) }?.let { pack?.restyle(it, px) ?: it }
                ?: (raw ?: info.getBadgedIcon(0)).toBitmap(px, px)
        return if (info.user == me) base else badge(base, info)
    }

    /** A picture you picked for an app: cropped to a square and cut to the icon shape. */
    fun custom(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.clipPath(if (shape == IconShape.System) IconShape.Squircle.path(px.toFloat()) else clip)
        c.drawBitmap(sq, null, android.graphics.Rect(0, 0, px, px), paint)
        return out
    }

    /** The phone's shape for "Phone default", otherwise the chosen shape. */
    private fun styled(d: Drawable): Bitmap? {
        if (shape == IconShape.System && d !is AdaptiveIconDrawable) return null
        if (shape == IconShape.System) return d.toBitmap(px, px)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.save()
        c.clipPath(clip)
        if (d is AdaptiveIconDrawable) {
            // Each layer is 108dp with a 72dp visible area: draw 1.5x, centered.
            val inset = (px * 0.25f).toInt()
            d.background?.let { it.setBounds(-inset, -inset, px + inset, px + inset); it.draw(c) }
            d.foreground?.let { it.setBounds(-inset, -inset, px + inset, px + inset); it.draw(c) }
        } else {
            // Older square icons: place them on a light plate so they match the others.
            c.drawColor(Color.WHITE)
            val inner = (px * 0.72f).toInt()
            val off = (px - inner) / 2
            d.setBounds(off, off, off + inner, off + inner)
            d.draw(c)
        }
        c.restore()
        return out
    }

    /** Pixel-style themed icon: the app's monochrome layer tinted with wallpaper colors. */
    private fun themedBitmap(d: Drawable): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mono = (d as? AdaptiveIconDrawable)?.monochrome ?: return null
        val bg = ctx.getColor(android.R.color.system_accent1_800)
        val fg = ctx.getColor(android.R.color.system_accent1_100)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        canvas.save()
        canvas.clipPath(clip)
        val layer = mono.mutate()
        layer.setTint(fg)
        val inset = (px * 0.25f).toInt()
        layer.setBounds(-inset, -inset, px + inset, px + inset)
        layer.draw(canvas)
        canvas.restore()
        return bmp
    }

    private fun badge(bmp: Bitmap, info: LauncherActivityInfo): Bitmap = runCatching {
        ctx.packageManager.getUserBadgedIcon(BitmapDrawable(ctx.resources, bmp), info.user).toBitmap(px, px)
    }.getOrDefault(bmp)

    /** Draws a small preview of [shape] (used in the Icon style picker). */
    companion object {
        fun shapePreview(shape: IconShape, px: Int, color: Int): Bitmap {
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            Canvas(b).drawPath(shape.path(px.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            return b
        }
    }
}
