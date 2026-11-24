package com.naveen.privatemap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val title: String, val sub: String, val lat: Double, val lon: Double)

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var myLoc: MyLocationNewOverlay
    private lateinit var input: EditText
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var destMarker: Marker? = null
    private val results = ArrayList<Place>()

    // Tamil Nadu box: lon min, lat min, lon max, lat max
    private val tnWest = 76.2
    private val tnSouth = 8.0
    private val tnEast = 80.4
    private val tnNorth = 13.6

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "PrivateMapNaveen/1.0 (personal use)"
        cfg.osmdroidBasePath = File(cacheDir, "osm")
        cfg.osmdroidTileCache = File(cacheDir, "osm/tiles")

        val root = FrameLayout(this)

        // ---- Map ----
        map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(7.0)
        map.controller.setCenter(GeoPoint(11.1271, 78.6569))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // ---- Blue dot (my location) ----
        myLoc = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        val dot = blueDot()
        myLoc.setPersonIcon(dot)
        myLoc.setPersonAnchor(0.5f, 0.5f)
        myLoc.setDirectionIcon(dot)
        myLoc.setDirectionAnchor(0.5f, 0.5f)
        map.overlays.add(myLoc)

        // ---- Search panel (top) ----
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(8), dp(8), dp(8), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(12).toFloat()
        panel.background = bg
        panel.elevation = dp(6).toFloat()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        input = EditText(this)
        input.hint = "Search destination"
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val go = Button(this)
        go.text = "Go"
        row.addView(go, LinearLayout.LayoutParams(-2, -2))
        panel.addView(row)

        status = TextView(this)
        status.setTextColor(Color.DKGRAY)
        status.visibility = View.GONE
        panel.addView(status)

        list = ListView(this)
        list.visibility = View.GONE
        panel.addView(list, LinearLayout.LayoutParams(-1, dp(280)))

        val pLp = FrameLayout.LayoutParams(-1, -2)
        pLp.gravity = Gravity.TOP
        pLp.setMargins(dp(10), dp(10), dp(10), 0)
        root.addView(panel, pLp)

        // ---- My location button (bottom right) ----
        val locBtn = Button(this)
        locBtn.text = "My location"
        val bLp = FrameLayout.LayoutParams(-2, -2)
        bLp.gravity = Gravity.BOTTOM or Gravity.END
        bLp.setMargins(0, 0, dp(14), dp(24))
        root.addView(locBtn, bLp)

        setContentView(root)

        // ---- Actions ----
        go.setOnClickListener { doSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(input.text.toString()); true
            } else false
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in results.indices) showDestination(results[pos])
        }
        locBtn.setOnClickListener { goToMyLocation() }

        if (hasLocationPermission()) startLocation()
        else requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), 1
        )
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasLocationPermission()) startLocation()
        else Toast.makeText(this, "Location permission is needed for the blue dot", Toast.LENGTH_LONG).show()
    }

    private fun startLocation() {
        myLoc.enableMyLocation()
        myLoc.runOnFirstFix {
            runOnUiThread {
                val p = myLoc.myLocation
                if (p != null) {
                    map.controller.setZoom(16.0)
                    map.controller.animateTo(p)
                }
            }
        }
    }

    private fun goToMyLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Allow location permission first", Toast.LENGTH_LONG).show()
            return
        }
        val p = myLoc.myLocation
        if (p == null) {
            Toast.makeText(this, "Finding your location... wait a little", Toast.LENGTH_SHORT).show()
            startLocation()
        } else {
            map.controller.setZoom(17.0)
            map.controller.animateTo(p)
        }
    }

    private fun blueDot(): Bitmap {
        val s = dp(28)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(70, 30, 120, 255)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = Color.WHITE
        c.drawCircle(s / 2f, s / 2f, s / 3.2f, p)
        p.color = Color.rgb(30, 120, 255)
        c.drawCircle(s / 2f, s / 2f, s / 4.2f, p)
        return bmp
    }

    // ---------------- Search ----------------

    private fun doSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Searching..."
        status.visibility = View.VISIBLE
        list.visibility = View.GONE
        val me = myLoc.myLocation
        Thread {
            var found: List<Place> = emptyList()
            try { found = searchPhoton(query, me) } catch (e: Exception) { }
            if (found.isEmpty()) {
                try { found = searchNominatim(query) } catch (e: Exception) { }
            }
            runOnUiThread { showResults(found, me) }
        }.start()
    }

    private fun showResults(found: List<Place>, me: GeoPoint?) {
        results.clear()
        results.addAll(found)
        if (found.isEmpty()) {
            status.text = "No place found in Tamil Nadu. Try another spelling or add the area name."
            list.visibility = View.GONE
            return
        }
        status.visibility = View.GONE
        val lines = found.map { p ->
            var d = ""
            if (me != null) {
                val r = FloatArray(1)
                Location.distanceBetween(me.latitude, me.longitude, p.lat, p.lon, r)
                d = String.format("  (%.1f km)", r[0] / 1000f)
            }
            p.title + d + "\n" + p.sub
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        list.visibility = View.VISIBLE
    }

    private fun showDestination(p: Place) {
        destMarker?.let { map.overlays.remove(it) }
        val m = Marker(map)
        m.position = GeoPoint(p.lat, p.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = p.title
        m.snippet = p.sub
        map.overlays.add(m)
        destMarker = m
        m.showInfoWindow()
        map.controller.setZoom(16.0)
        map.controller.animateTo(m.position)
        list.visibility = View.GONE
        map.invalidate()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PrivateMapNaveen/1.0 (personal use)")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun searchPhoton(q: String, me: GeoPoint?): List<Place> {
        var url = "https://photon.komoot.io/api/?limit=8&bbox=$tnWest,$tnSouth,$tnEast,$tnNorth&q=" +
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
            if (lat < tnSouth || lat > tnNorth || lon < tnWest || lon > tnEast) continue
            val name = pr.optString("name", "")
            val street = pr.optString("street", "")
            val parts = listOf(street, pr.optString("district", ""), pr.optString("city", ""), pr.optString("state", ""))
                .filter { it.isNotEmpty() && it != name }.distinct()
            val title = if (name.isNotEmpty()) name else if (street.isNotEmpty()) street else "Place"
            out.add(Place(title, parts.joinToString(", "), lat, lon))
        }
        return out
    }

    private fun searchNominatim(q: String): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=8&countrycodes=in" +
                "&bounded=1&viewbox=$tnWest,$tnNorth,$tnEast,$tnSouth&q=" + URLEncoder.encode(q, "UTF-8")
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

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (hasLocationPermission()) myLoc.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        myLoc.disableMyLocation()
        map.onPause()
    }
}
