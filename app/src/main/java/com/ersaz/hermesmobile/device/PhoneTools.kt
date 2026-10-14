package com.ersaz.hermesmobile.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import com.ersaz.hermesmobile.agent.tools.Tool
import com.ersaz.hermesmobile.agent.tools.ToolContext
import com.ersaz.hermesmobile.agent.tools.fail
import com.ersaz.hermesmobile.agent.tools.int
import com.ersaz.hermesmobile.agent.tools.obj
import com.ersaz.hermesmobile.agent.tools.ok
import com.ersaz.hermesmobile.agent.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Deeper phone integration: calendar, alarms/timers, contacts, location, apps, sharing, flashlight, volume. */
class PhoneTool : Tool(
    "phone", "device",
    "Phone features beyond android_device. Calendar: calendar_list {from,to} / calendar_add {title,start,end,location,notes}; " +
        "alarm_set {hour,minute,label}; timer_set {seconds,label}; contacts_search {query}; location (last known position); " +
        "dial {number} (opens the dialer, never calls by itself); apps_list / app_launch {app}; share_file {path}; flashlight {on}; volume {level 0-100}. " +
        "Times are ISO local time like 2026-10-20T09:30. Sensitive actions ask the user for Android permission first.",
    "📱",
) {
    override val parameters = obj(
        "action" to str("Action", listOf("calendar_list", "calendar_add", "alarm_set", "timer_set", "contacts_search", "location", "dial",
            "apps_list", "app_launch", "share_file", "flashlight", "volume")),
        "title" to str("Event title"), "start" to str("Start time ISO (calendar_add) / range start (calendar_list; default now)"),
        "end" to str("End time ISO (calendar_add; default start+1h) / range end (calendar_list; default +7 days)"),
        "location" to str("Event location"), "notes" to str("Event notes"),
        "hour" to int("Hour 0-23"), "minute" to int("Minute 0-59"), "seconds" to int("Timer length"), "label" to str("Alarm/timer label"),
        "query" to str("Search text"), "number" to str("Phone number"), "app" to str("App label or package"),
        "path" to str("File path in the sandbox"), "on" to str("true/false"), "level" to int("0-100"),
        required = listOf("action"),
    )

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private fun parseTime(s: String): Long? = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd").firstNotNullOfOrNull {
        try { SimpleDateFormat(it, Locale.US).parse(s)?.time } catch (_: Exception) { null }
    }

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val rt = ctx.runtime
        val c: Context = rt.app
        suspend fun need(vararg p: String): String? = if (rt.permissions.ensure(*p)) null else fail("Android permission denied or unavailable: ${p.joinToString { it.substringAfterLast('.') }}. Ask the user to allow it.")
        return try { when (args.optString("action")) {
            "calendar_list" -> {
                need(Manifest.permission.READ_CALENDAR)?.let { return it }
                val from = parseTime(args.optString("start")) ?: System.currentTimeMillis()
                val to = parseTime(args.optString("end")) ?: (from + 7 * 86_400_000L)
                withContext(Dispatchers.IO) {
                    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
                    val out = JSONArray()
                    c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                        while (cur.moveToNext() && out.length() < 60) out.put(JSONObject().put("title", cur.getString(0)).put("start", iso.format(Date(cur.getLong(1))))
                            .put("end", iso.format(Date(cur.getLong(2)))).put("location", cur.getString(3).orEmpty()).put("all_day", cur.getInt(4) == 1))
                    }
                    JSONObject().put("events", out).toString()
                }
            }
            "calendar_add" -> {
                need(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)?.let { return it }
                val start = parseTime(args.optString("start")) ?: return fail("start is required (ISO local time)")
                val end = parseTime(args.optString("end")) ?: (start + 3_600_000L)
                withContext(Dispatchers.IO) {
                    val calId = c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
                        "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null }
                        ?: return@withContext fail("No writable calendar found on this phone.")
                    val v = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, args.optString("title", "Event"))
                        put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        put(CalendarContract.Events.EVENT_LOCATION, args.optString("location")); put(CalendarContract.Events.DESCRIPTION, args.optString("notes"))
                    }
                    val u = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
                    if (u == null) fail("Couldn't create the event") else ok("created" to args.optString("title"), "start" to iso.format(Date(start)))
                }
            }
            "alarm_set" -> {
                val h = args.optInt("hour", -1); val m = args.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) return fail("hour (0-23) and minute (0-59) are required")
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Hermes")).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ok("alarm" to "%02d:%02d".format(h, m))
            }
            "timer_set" -> {
                val secs = args.optInt("seconds", 0).takeIf { it > 0 } ?: return fail("seconds is required")
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Hermes"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ok("timer_seconds" to secs)
            }
            "contacts_search" -> {
                need(Manifest.permission.READ_CONTACTS)?.let { return it }
                val q = args.optString("query").ifBlank { return fail("query is required") }
                withContext(Dispatchers.IO) {
                    val out = JSONArray()
                    c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { cur ->
                        while (cur.moveToNext() && out.length() < 25) out.put(JSONObject().put("name", cur.getString(0)).put("number", cur.getString(1)))
                    }
                    JSONObject().put("contacts", out).toString()
                }
            }
            "location" -> {
                need(Manifest.permission.ACCESS_COARSE_LOCATION)?.let { return it }
                val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best: Location? = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }.maxByOrNull { it.time }
                if (best == null) fail("No location fix available (is Location turned on?)")
                else ok("lat" to best.latitude, "lon" to best.longitude, "accuracy_m" to best.accuracy.toInt(), "age_s" to (System.currentTimeMillis() - best.time) / 1000)
            }
            "dial" -> {
                val n = args.optString("number").ifBlank { return fail("number is required") }
                withContext(Dispatchers.Main) { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ok("dialer_opened" to n)
            }
            "apps_list" -> {
                val pm = c.packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.sortedBy { it.first.lowercase() }
                JSONObject().put("apps", JSONArray(apps.take(200).map { "${it.first} (${it.second})" })).toString()
            }
            "app_launch" -> {
                val q = args.optString("app").ifBlank { return fail("app is required") }
                val pm = c.packageManager
                val hit = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { it.activityInfo.packageName.equals(q, true) || it.loadLabel(pm).toString().equals(q, true) }
                    ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).firstOrNull { it.loadLabel(pm).toString().contains(q, true) }
                    ?: return fail("No app matching '$q'")
                val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return fail("Can't launch it")
                withContext(Dispatchers.Main) { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ok("launched" to hit.loadLabel(pm).toString())
            }
            "share_file" -> {
                val f = rt.sandbox.hostPath(args.optString("path").ifBlank { return fail("path is required") })
                if (!f.exists() || !f.isFile) return fail("File not found: ${args.optString("path")}")
                // Copy into the app's shared dir so FileProvider can serve it (the rootfs path isn't exposed).
                val shared = File(c.cacheDir, "shared").apply { mkdirs() }
                val copy = File(shared, f.name).also { f.copyTo(it, overwrite = true) }
                val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", copy)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ok("sharing" to f.name)
            }
            "flashlight" -> {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return fail("No flashlight")
                val on = args.optString("on", "true").equals("true", true)
                cm.setTorchMode(id, on); ok("flashlight" to on)
            }
            "volume" -> {
                val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (args.has("level")) am.setStreamVolume(AudioManager.STREAM_MUSIC, (args.optInt("level") * max / 100).coerceIn(0, max), 0)
                ok("media_volume_percent" to am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            }
            else -> fail("Unknown action")
        } } catch (e: SecurityException) { fail("Permission denied: ${e.message}") }
    }
}

/** Current weather and a short forecast from Open-Meteo (no API key). */
class WeatherTool : Tool(
    "weather", "web",
    "Current weather and 3-day forecast for a place name (e.g. \"Tehran\") or lat/lon. Uses Open-Meteo; no key needed.",
    "⛅",
) {
    override val parameters = obj(
        "place" to str("City or place name"),
        "lat" to str("Latitude (instead of place)"), "lon" to str("Longitude"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val http = ctx.runtime.http
        fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "hermes-agent-android").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}"); JSONObject(r.body?.string().orEmpty())
        }
        var lat = args.optString("lat").toDoubleOrNull(); var lon = args.optString("lon").toDoubleOrNull(); var name = args.optString("place")
        if (lat == null || lon == null) {
            if (name.isBlank()) return@withContext fail("place or lat/lon is required")
            val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(name, "UTF-8"))
            val r = g.optJSONArray("results")?.optJSONObject(0) ?: return@withContext fail("Couldn't find '$name'")
            lat = r.getDouble("latitude"); lon = r.getDouble("longitude"); name = listOf(r.optString("name"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
        }
        val w = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,wind_speed_10m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum&forecast_days=3&timezone=auto")
        JSONObject().put("place", name).put("current", w.optJSONObject("current")).put("daily", w.optJSONObject("daily"))
            .put("units", w.optJSONObject("current_units")).put("weather_code_note", "WMO codes: 0 clear, 1-3 cloudy, 45/48 fog, 51-67 rain/drizzle, 71-77 snow, 80-82 showers, 95-99 thunderstorm").toString()
    }
}
