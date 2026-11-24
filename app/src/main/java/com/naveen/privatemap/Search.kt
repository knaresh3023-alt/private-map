package com.naveen.privatemap

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Place(
    val title: String,
    val sub: String,
    val lat: Double,
    val lon: Double,
    val approx: Boolean = false
)

object Search {
    // Tamil Nadu box
    const val W = 76.2
    const val S = 8.0
    const val E = 80.4
    const val N = 13.6

    private val pool = Executors.newCachedThreadPool()
    private val cache = HashMap<String, Place>()

    fun httpGet(url: String, timeoutMs: Int = 6000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "PrivateMapNaveen/1.0 (personal use)")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun photon(q: String, me: GeoPoint?, limit: Int = 8): List<Place> {
        var url = "https://photon.komoot.io/api/?limit=$limit&bbox=$W,$S,$E,$N&q=" +
                URLEncoder.encode(q, "UTF-8")
        if (me != null) url += "&lat=${me.latitude}&lon=${me.longitude}"
        val feats = JSONObject(httpGet(url)).getJSONArray("features")
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val co = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pr = f.getJSONObject("properties")
            val lon = co.getDouble(0)
            val lat = co.getDouble(1)
            if (lat < S || lat > N || lon < W || lon > E) continue
            val name = pr.optString("name", "")
            val street = pr.optString("street", "")
            val parts = listOf(street, pr.optString("district", ""), pr.optString("city", ""), pr.optString("state", ""))
                .filter { it.isNotEmpty() && it != name }.distinct()
            val title = if (name.isNotEmpty()) name else if (street.isNotEmpty()) street else "Place"
            out.add(Place(title, parts.joinToString(", "), lat, lon))
        }
        return out
    }

    fun nominatim(q: String, limit: Int = 8): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=$limit&countrycodes=in" +
                "&bounded=1&viewbox=$W,$N,$E,$S&q=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(httpGet(url))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("display_name", "")
            val title = full.substringBefore(",").ifEmpty { "Place" }
            val sub = full.substringAfter(",", "").trim()
            out.add(Place(title, sub, o.getDouble("lat"), o.getDouble("lon")))
        }
        return out
    }

    // Road route from OSRM (free)
    fun route(a: GeoPoint, b: GeoPoint): List<GeoPoint>? {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${a.longitude},${a.latitude};${b.longitude},${b.latitude}" +
                "?overview=simplified&geometries=geojson"
        val js = JSONObject(httpGet(url, 7000))
        val routes = js.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val out = ArrayList<GeoPoint>()
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            out.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }
        return if (out.size >= 2) out else null
    }

    // ---------- Drop address search (fast, parallel) ----------

    private fun cleanSegs(address: String, area: String): List<String> {
        val t = address.trimEnd()
        val trunc = t.endsWith("\u2026") || t.endsWith("...")
        var raw = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        raw = if (trunc) {
            if (raw.size > 1) raw.dropLast(1)
            else raw.map { it.replace("\u2026", "").replace("...", "").trim() }
        } else raw
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val tn = Regex("(?i)\\btamil\\s*nadu\\b")
        val pin = Regex("\\b\\d{6}\\b")
        val num = Regex("^[\\d/\\-\\s]+$")
        val plus = Regex("^[A-Z0-9]{4,8}\\+[A-Z0-9]*")
        for (r in raw) {
            val s = r.replace(tn, "").replace(pin, "").trim()
            if (s.isEmpty()) continue
            if (s.equals("india", true)) continue
            if (num.matches(s)) continue
            if (plus.containsMatchIn(s)) continue
            if (seen.add(s.lowercase())) out.add(s)
        }
        val a = area.trim()
        if (a.isNotEmpty() && out.none { it.equals(a, true) }) out.add(a)
        return out
    }

    private fun straightKm(me: GeoPoint, p: Place): Double {
        val r = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, p.lat, p.lon, r)
        return r[0] / 1000.0
    }

    private fun pick(list: List<Place>, me: GeoPoint?, maxKm: Double?, strict: Boolean): Place? {
        if (list.isEmpty()) return null
        if (me == null || maxKm == null) return list.first()
        val limit = maxKm * 1.1 + 2.0
        val ok = list.filter { straightKm(me, it) <= limit }
        if (ok.isNotEmpty()) return ok.first()
        return if (strict) null else list.first()
    }

    private fun race(tasks: List<() -> List<Place>>, me: GeoPoint?, maxKm: Double?, strict: Boolean, ms: Long): Place? {
        if (tasks.isEmpty()) return null
        val cs = ExecutorCompletionService<List<Place>>(pool)
        for (t in tasks) {
            cs.submit(Callable<List<Place>> {
                try { t() } catch (e: Exception) { emptyList() }
            })
        }
        val end = System.currentTimeMillis() + ms
        var left = tasks.size
        while (left > 0) {
            val rem = end - System.currentTimeMillis()
            if (rem <= 0) break
            val f = cs.poll(rem, TimeUnit.MILLISECONDS) ?: break
            left--
            val list = try { f.get() } catch (e: Exception) { emptyList<Place>() }
            val p = pick(list, me, maxKm, strict)
            if (p != null) return p
        }
        return null
    }

    fun find(d: Drop, me: GeoPoint?): Place? {
        synchronized(cache) { cache[d.key]?.let { return it } }
        val segs = cleanSegs(d.address, d.area)
        val area = d.area.trim()
        val maxKm = d.dropKm?.let { it + (d.pickupKm ?: 0.0) }

        val qFull = segs.take(4).joinToString(", ")
        val qMid = if (segs.size > 2) segs.takeLast(3).joinToString(", ") else ""
        val t1 = ArrayList<() -> List<Place>>()
        if (qFull.isNotEmpty()) {
            t1.add { photon(qFull, me) }
            t1.add { nominatim(qFull) }
        }
        if (qMid.isNotEmpty() && qMid != qFull) t1.add { photon(qMid, me) }

        var res = race(t1, me, maxKm, true, 3500)
        if (res == null && area.isNotEmpty()) {
            val t2 = listOf<() -> List<Place>>({ photon(area, me) }, { nominatim(area) })
            res = race(t2, me, maxKm, false, 3500)?.copy(approx = true)
        }
        if (res != null) synchronized(cache) { cache[d.key] = res }
        return res
    }
}
