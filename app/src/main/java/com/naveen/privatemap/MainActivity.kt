package com.naveen.privatemap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : Activity(), DropListener {

    private lateinit var map: MapView
    private lateinit var myLoc: MyLocationNewOverlay
    private lateinit var input: EditText
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var autoBtn: Button
    private var destMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val results = ArrayList<Place>()
    private var token = 0
    private var autoActive = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "PrivateMapNaveen/1.0 (personal use)"
        cfg.osmdroidBasePath = File(cacheDir, "osm")
        cfg.osmdroidTileCache = File(cacheDir, "osm/tiles")

        val root = FrameLayout(this)

        // ---- Map (CARTO Voyager style, looks like Google Maps) ----
        map = MapView(this)
        val voyager = XYTileSource(
            "CartoVoyager", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
            )
        )
        map.setTileSource(voyager)
        map.isTilesScaledToDpi = true
        map.setMultiTouchControls(true)
        map.keepScreenOn = true
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(7.0)
        map.controller.setCenter(GeoPoint(11.1271, 78.6569))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // ---- Blue dot ----
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

        // ---- My location button ----
        val locBtn = Button(this)
        locBtn.text = "My location"
        val bLp = FrameLayout.LayoutParams(-2, -2)
        bLp.gravity = Gravity.BOTTOM or Gravity.END
        bLp.setMargins(0, 0, dp(14), dp(24))
        root.addView(locBtn, bLp)

        // ---- Auto search OFF warning ----
        autoBtn = Button(this)
        autoBtn.text = "Auto search is OFF - tap to turn ON"
        autoBtn.setTextColor(Color.WHITE)
        val ab = GradientDrawable()
        ab.setColor(Color.rgb(200, 40, 40))
        ab.cornerRadius = dp(8).toFloat()
        autoBtn.background = ab
        autoBtn.visibility = View.GONE
        val aLp = FrameLayout.LayoutParams(-2, -2)
        aLp.gravity = Gravity.BOTTOM or Gravity.START
        aLp.setMargins(dp(14), 0, 0, dp(24))
        root.addView(autoBtn, aLp)

        // ---- Map credit ----
        val credit = TextView(this)
        credit.text = "\u00A9 OpenStreetMap \u00A9 CARTO"
        credit.textSize = 9f
        credit.setTextColor(Color.DKGRAY)
        val cLp = FrameLayout.LayoutParams(-2, -2)
        cLp.gravity = Gravity.BOTTOM or Gravity.START
        cLp.setMargins(dp(4), 0, 0, dp(2))
        root.addView(credit, cLp)

        setContentView(root)

        go.setOnClickListener { doSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(input.text.toString()); true
            } else false
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in results.indices) {
                token++
                autoActive = false
                showDestination(results[pos], false)
            }
        }
        locBtn.setOnClickListener { goToMyLocation() }
        autoBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

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
                if (destMarker == null) {
                    val p = myLoc.myLocation
                    if (p != null) {
                        map.controller.setZoom(16.0)
                        map.controller.animateTo(p)
                    }
                }
            }
        }
    }

    private fun myPoint(): GeoPoint? {
        myLoc.myLocation?.let { return it }
        if (!hasLocationPermission()) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (l != null) GeoPoint(l.latitude, l.longitude) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun goToMyLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Allow location permission first", Toast.LENGTH_LONG).show()
            return
        }
        val p = myPoint()
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

    // ---------------- Auto search from popup ----------------

    override fun onDrop(d: Drop) {
        token++
        val my = token
        clearResult()
        autoActive = true
        list.visibility = View.GONE
        status.visibility = View.GONE
        input.setText(if (d.address.isNotEmpty()) d.address else d.area)
        val me = myPoint()
        Thread {
            val place = try { Search.find(d, me) } catch (e: Exception) { null }
            runOnUiThread {
                if (place != null && my == token) showDestination(place, true)
            }
        }.start()
    }

    override fun onPopupGone() {
        token++
        if (!autoActive) return
        autoActive = false
        clearResult()
        input.setText("")
        val me = myPoint()
        if (me != null) {
            map.controller.setZoom(16.0)
            map.controller.animateTo(me)
        }
    }

    private fun clearResult() {
        destMarker?.let { map.overlays.remove(it) }
        destMarker = null
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        map.invalidate()
    }

    // ---------------- Manual search ----------------

    private fun doSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Searching..."
        status.visibility = View.VISIBLE
        list.visibility = View.GONE
        val me = myPoint()
        Thread {
            var found: List<Place> = emptyList()
            try { found = Search.photon(query, me) } catch (e: Exception) { }
            if (found.isEmpty()) {
                try { found = Search.nominatim(query) } catch (e: Exception) { }
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

    // ---------------- Pin + route ----------------

    private fun showDestination(p: Place, auto: Boolean) {
        clearResult()
        val pos = GeoPoint(p.lat, p.lon)
        val m = Marker(map)
        m.position = pos
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        if (!auto) {
            m.title = p.title
            m.snippet = p.sub
        }
        map.overlays.add(m)
        destMarker = m
        if (!auto) m.showInfoWindow()
        list.visibility = View.GONE

        val me = myPoint()
        if (me != null) {
            drawRoute(me, pos, token)
            if (auto) fitBelowPopup(me, pos) else fitAll(me, pos)
        } else {
            map.controller.setZoom(if (p.approx) 14.0 else 16.0)
            map.controller.animateTo(pos)
        }
        map.invalidate()
    }

    private fun drawRoute(a: GeoPoint, b: GeoPoint, my: Int) {
        val line = Polyline()
        line.outlinePaint.color = Color.rgb(26, 115, 232)
        line.outlinePaint.strokeWidth = dp(5).toFloat()
        line.outlinePaint.strokeCap = Paint.Cap.ROUND
        line.setPoints(listOf(a, b)) // straight line first (instant)
        map.overlays.add(0, line)
        routeLine = line
        Thread {
            val pts = try { Search.route(a, b) } catch (e: Exception) { null }
            if (pts != null) {
                runOnUiThread {
                    if (my == token && routeLine === line) {
                        line.setPoints(pts)
                        map.invalidate()
                    }
                }
            }
        }.start()
    }

    // Show both points in the LOWER half of the screen (popup covers the top)
    private fun fitBelowPopup(a: GeoPoint, b: GeoPoint) {
        val n = maxOf(a.latitude, b.latitude)
        val s = minOf(a.latitude, b.latitude)
        val e = maxOf(a.longitude, b.longitude)
        val w = minOf(a.longitude, b.longitude)
        val span = maxOf(n - s, 0.004)
        val lonSpan = maxOf(e - w, 0.004)
        val south = s - span * 0.15
        val north = south + (span * 1.3) / 0.5
        val west = w - lonSpan * 0.15
        val east = e + lonSpan * 0.15
        map.post { map.zoomToBoundingBox(BoundingBox(north, east, south, west), false) }
    }

    private fun fitAll(a: GeoPoint, b: GeoPoint) {
        val n = maxOf(a.latitude, b.latitude)
        val s = minOf(a.latitude, b.latitude)
        val e = maxOf(a.longitude, b.longitude)
        val w = minOf(a.longitude, b.longitude)
        val span = maxOf(n - s, 0.004)
        val lonSpan = maxOf(e - w, 0.004)
        val box = BoundingBox(n + span * 0.3, e + lonSpan * 0.3, s - span * 0.3, w - lonSpan * 0.3)
        map.post { map.zoomToBoundingBox(box, false) }
    }

    // ---------------- Lifecycle ----------------

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/")
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (hasLocationPermission()) myLoc.enableMyLocation()
        autoBtn.visibility = if (accessibilityOn()) View.GONE else View.VISIBLE
        Bus.resetFlag = true
        Bus.listener = this
    }

    override fun onPause() {
        super.onPause()
        Bus.listener = null
        myLoc.disableMyLocation()
        map.onPause()
    }
}
