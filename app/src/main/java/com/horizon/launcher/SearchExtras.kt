package com.horizon.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Quick answers for the drawer search: math, unit conversion, phone settings pages and contacts.
// ---------------------------------------------------------------------------------------------

/** "12*4+3", "15% of 84", "(2+3)^2", "100/7" → a formatted answer, or null if it isn't math. */
fun calculate(input: String): String? {
    val raw = input.trim().lowercase(Locale.ROOT)
    if (raw.isEmpty()) return null
    // "15% of 84" and "15 percent of 84"
    Regex("""^([\d.]+)\s*(%|percent)\s*of\s*([\d.,]+)$""").find(raw)?.let { m ->
        val p = m.groupValues[1].toDoubleOrNull() ?: return null
        val n = m.groupValues[3].replace(",", "").toDoubleOrNull() ?: return null
        return format(p / 100.0 * n)
    }
    val expr = raw.replace("×", "*").replace("x", "*").replace("÷", "/").replace(",", "").replace(" ", "")
    // Must look like math: digits plus at least one operator, nothing else.
    if (!Regex("""^[\d.+\-*/^%()]+$""").matches(expr)) return null
    if (!Regex("""\d""").containsMatchIn(expr) || !Regex("""[+\-*/^%]""").containsMatchIn(expr.drop(1))) return null
    return runCatching { MathParser(expr).parse() }.getOrNull()?.takeIf { it.isFinite() }?.let(::format)
}

private fun format(v: Double): String {
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return String.format(Locale.getDefault(), "%,d", v.toLong())
    val bd = BigDecimal(v).round(MathContext(10)).stripTrailingZeros()
    return bd.toPlainString()
}

/** Small recursive-descent parser: + - * / % ^ and parentheses, with the usual precedence. */
private class MathParser(private val s: String) {
    private var i = 0
    fun parse(): Double {
        val v = expr()
        require(i == s.length)
        return v
    }
    private fun expr(): Double {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i++]
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }
    private fun term(): Double {
        var v = power()
        while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
            val op = s[i++]
            // "50%" on its own means 0.5
            if (op == '%' && (i == s.length || s[i] in "+-)*/")) { v /= 100.0; continue }
            val r = power()
            v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
        }
        return v
    }
    private fun power(): Double {
        val b = unary()
        if (i < s.length && s[i] == '^') { i++; return Math.pow(b, power()) }
        return b
    }
    private fun unary(): Double {
        if (i < s.length && s[i] == '-') { i++; return -unary() }
        if (i < s.length && s[i] == '+') { i++; return unary() }
        return atom()
    }
    private fun atom(): Double {
        if (i < s.length && s[i] == '(') {
            i++
            val v = expr()
            require(i < s.length && s[i] == ')')
            i++
            return v
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        require(i > start)
        return s.substring(start, i).toDouble()
    }
}

/** "5 km to mi", "72f in c", "3 cups to ml", "180 lb to kg" → "3.107 mi", or null. */
fun convertUnits(input: String): String? {
    val m = Regex("""^(-?[\d.,]+)\s*([a-z°/ ]+?)\s+(to|in|as)\s+([a-z°/ ]+)$""").find(input.trim().lowercase(Locale.ROOT)) ?: return null
    val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
    val from = unit(m.groupValues[2].trim()) ?: return null
    val to = unit(m.groupValues[4].trim()) ?: return null
    if (from.kind != to.kind) return null
    val result = if (from.kind == "temp") {
        val c = when (from.symbol) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
        when (to.symbol) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
    } else value * from.factor / to.factor
    return "${format(result)} ${to.symbol}"
}

private data class UnitDef(val kind: String, val factor: Double, val symbol: String)

private fun unit(name: String): UnitDef? = when (name.removeSuffix(".")) {
    "mm", "millimeter", "millimeters" -> UnitDef("len", 0.001, "mm")
    "cm", "centimeter", "centimeters" -> UnitDef("len", 0.01, "cm")
    "m", "meter", "meters", "metre", "metres" -> UnitDef("len", 1.0, "m")
    "km", "kilometer", "kilometers" -> UnitDef("len", 1000.0, "km")
    "in", "inch", "inches", "\"" -> UnitDef("len", 0.0254, "in")
    "ft", "foot", "feet", "'" -> UnitDef("len", 0.3048, "ft")
    "yd", "yard", "yards" -> UnitDef("len", 0.9144, "yd")
    "mi", "mile", "miles" -> UnitDef("len", 1609.344, "mi")
    "mg" -> UnitDef("mass", 0.001, "mg")
    "g", "gram", "grams" -> UnitDef("mass", 1.0, "g")
    "kg", "kilo", "kilos", "kilogram", "kilograms" -> UnitDef("mass", 1000.0, "kg")
    "oz", "ounce", "ounces" -> UnitDef("mass", 28.349523125, "oz")
    "lb", "lbs", "pound", "pounds" -> UnitDef("mass", 453.59237, "lb")
    "ml", "milliliter", "milliliters" -> UnitDef("vol", 1.0, "ml")
    "l", "liter", "liters", "litre", "litres" -> UnitDef("vol", 1000.0, "L")
    "floz", "fl oz" -> UnitDef("vol", 29.5735, "fl oz")
    "cup", "cups" -> UnitDef("vol", 236.588, "cups")
    "gal", "gallon", "gallons" -> UnitDef("vol", 3785.41, "gal")
    "c", "°c", "celsius" -> UnitDef("temp", 1.0, "°C")
    "f", "°f", "fahrenheit" -> UnitDef("temp", 1.0, "°F")
    "k", "kelvin" -> UnitDef("temp", 1.0, "K")
    "kph", "km/h", "kmh" -> UnitDef("speed", 1.0, "km/h")
    "mph" -> UnitDef("speed", 1.609344, "mph")
    else -> null
}

/** A page of the phone's Settings app that search can jump to. */
data class SettingsPage(val label: String, val keywords: String, val action: String)

private val SETTINGS_PAGES = listOf(
    SettingsPage("Wi-Fi", "wifi wi-fi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
    SettingsPage("Bluetooth", "bluetooth headphones earbuds pair", Settings.ACTION_BLUETOOTH_SETTINGS),
    SettingsPage("Network & internet", "mobile data cellular sim hotspot internet", Settings.ACTION_WIRELESS_SETTINGS),
    SettingsPage("Airplane mode", "airplane flight mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    SettingsPage("Display", "display brightness dark theme screen timeout font size", Settings.ACTION_DISPLAY_SETTINGS),
    SettingsPage("Sound & vibration", "sound volume ringtone vibration", Settings.ACTION_SOUND_SETTINGS),
    SettingsPage("Do Not Disturb", "do not disturb dnd silence quiet", "android.settings.ZEN_MODE_SETTINGS"),
    SettingsPage("Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"),
    SettingsPage("Battery", "battery power saver charging", Intent.ACTION_POWER_USAGE_SUMMARY),
    SettingsPage("Battery saver", "battery saver power", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    SettingsPage("Apps", "apps applications uninstall permissions", Settings.ACTION_APPLICATION_SETTINGS),
    SettingsPage("Default apps", "default apps browser home launcher", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    SettingsPage("Storage", "storage space memory free up", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    SettingsPage("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    SettingsPage("Security & privacy", "security privacy lock screen fingerprint face unlock password pin", Settings.ACTION_SECURITY_SETTINGS),
    SettingsPage("Accessibility", "accessibility talkback magnification", Settings.ACTION_ACCESSIBILITY_SETTINGS),
    SettingsPage("Date & time", "date time clock timezone", Settings.ACTION_DATE_SETTINGS),
    SettingsPage("Languages", "language keyboard input", Settings.ACTION_LOCALE_SETTINGS),
    SettingsPage("NFC & payments", "nfc tap to pay contactless", Settings.ACTION_NFC_SETTINGS),
    SettingsPage("Developer options", "developer options usb debugging adb", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    SettingsPage("About phone", "about phone android version model", Settings.ACTION_DEVICE_INFO_SETTINGS),
    SettingsPage("System update", "system update software", "android.settings.SYSTEM_UPDATE_SETTINGS"),
)

fun searchSettings(query: String): List<SettingsPage> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.length < 2) return emptyList()
    return SETTINGS_PAGES.filter { p ->
        p.label.lowercase(Locale.ROOT).startsWith(q) || p.keywords.split(' ').any { it.startsWith(q) }
    }.take(3)
}

fun openSettingsPage(context: Context, page: SettingsPage) {
    runCatching { context.startActivity(Intent(page.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { context.startSafely(Intent(Settings.ACTION_SETTINGS)) }
}

/** One person from Contacts, with their first phone number if they have one. */
data class ContactHit(val name: String, val number: String?, val lookupUri: Uri)

fun hasContactsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

fun searchContacts(context: Context, query: String, limit: Int = 3): List<ContactHit> {
    val q = query.trim()
    if (q.length < 2 || !hasContactsPermission(context)) return emptyList()
    return runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        val proj = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val out = mutableListOf<ContactHit>()
        context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val name = c.getString(2) ?: continue
                val number = if (c.getInt(3) > 0) firstNumber(context, id) else null
                out += ContactHit(name, number, ContactsContract.Contacts.getLookupUri(id, c.getString(1)))
            }
        }
        out
    }.getOrDefault(emptyList())
}

private fun firstNumber(context: Context, contactId: Long): String? = runCatching {
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()
