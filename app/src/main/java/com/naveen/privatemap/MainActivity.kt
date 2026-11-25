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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
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
    private var autoMarker: Marker? = null
    private var autoLine: Polyline? = null
    private var manualMarker: Marker? = null
    private var manualLine: Polyline? = null
    private var manualToken = 0
    private var moveMode = 0 // 0 = move only for a new popup, 1 = never move by itself
    private var typedText = ""
    private var hasSaved = false
    private lateinit var settingsBox: ScrollView
    private lateinit var closeMarkBtn: Button
    private val palette = intArrayOf(
        Color.rgb(26, 115, 232), Color.rgb(245, 124, 0), Color.rgb(46, 158, 79),
        Color.rgb(217, 48, 37), Color.rgb(142, 36, 170), Color.rgb(32, 33, 36)
    )
    private var manualColor = palette[1]
    private var popupColor = palette[0]
    private val results = ArrayList<Place>()
    private var token = 0
    private var autoActive = false

    private class Theme(
        val name: String,
        val base: ITileSource,
        val ref: ITileSource?,
        val sat: Boolean,
        val credit: String
    )

    private lateinit var themes: List<Theme>
    private lateinit var settings: LinearLayout
    private lateinit var creditView: TextView
    private lateinit var sizeLabel: TextView
    private var refOverlay: TilesOverlay? = null
    private var themeIdx = 0
    private var sizePct = 30 // 30 = 130%

    private fun esri(name: String, service: String, maxZ: Int, ending: String): ITileSource =
        object : OnlineTileSourceBase(
            name, 0, maxZ, 256, ending,
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
        }

    private fun buildThemes(): List<Theme> = listOf(
        Theme("Google-like (Esri Street)", esri("EsriStreet", "World_Street_Map", 19, ".jpg"), null, false, "\u00A9 Esri"),
        Theme("OpenStreetMap", TileSourceFactory.MAPNIK, null, false, "\u00A9 OpenStreetMap"),
        Theme("Light",
            esri("EsriLightBase", "Canvas/World_Light_Gray_Base", 16, ".png"),
            esri("EsriLightRef", "Canvas/World_Light_Gray_Reference", 16, ".png"), false, "\u00A9 Esri"),
        Theme("Night",
            esri("EsriDarkBase", "Canvas/World_Dark_Gray_Base", 16, ".png"),
            esri("EsriDarkRef", "Canvas/World_Dark_Gray_Reference", 16, ".png"), false, "\u00A9 Esri"),
        Theme("Satellite", esri("EsriSat", "World_Imagery", 19, ".jpg"), null, true, "\u00A9 Esri")
    )

    private fun factor(): Float = 1f + sizePct / 100f

    private fun applyTheme() {
        val t = themes[themeIdx]
        refOverlay?.let {
            map.overlays.remove(it)
            it.onDetach(map)
        }
        refOverlay = null
        map.setTileSource(t.base)
        if (t.ref != null) {
            val ov = TilesOverlay(MapTileProviderBasic(applicationContext, t.ref), this)
            ov.loadingBackgroundColor = Color.TRANSPARENT
            ov.loadingLineColor = Color.TRANSPARENT
            map.overlays.add(0, ov)
            refOverlay = ov
        }
        map.setTilesScaleFactor(if (t.sat) 1f else factor())
        creditView.text = t.credit
        map.invalidate()
    }

    private fun routeIndex(): Int {
        val r = refOverlay ?: return 0
        val i = map.overlays.indexOf(r)
        return if (i >= 0) i + 1 else 0
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "PrivateMapNaveen/1.0 (personal use)"
        cfg.osmdroidBasePath = File(cacheDir, "osm")
        cfg.osmdroidTileCache = File(cacheDir, "osm/tiles")

        val root = FrameLayout(this)

        val prefs = getSharedPreferences("pm", Context.MODE_PRIVATE)
        themeIdx = prefs.getInt("theme", 0)
        sizePct = prefs.getInt("size", 30)
        themes = buildThemes()
        if (themeIdx !in themes.indices) themeIdx = 0

        // ---- Map ----
        map = MapView(this)
        map.setMultiTouchControls(true)
        map.keepScreenOn = true
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        moveMode = prefs.getInt("move", 0)
        manualColor = prefs.getInt("mcolor", palette[1])
        popupColor = prefs.getInt("pcolor", palette[0])
        val sLat = prefs.getString("lat", null)?.toDoubleOrNull()
        val sLon = prefs.getString("lon", null)?.toDoubleOrNull()
        val sZoom = prefs.getString("zoom", null)?.toDoubleOrNull()
        val sRot = prefs.getString("rot", null)?.toFloatOrNull()
        hasSaved = sLat != null && sLon != null && sZoom != null
        map.controller.setZoom(sZoom ?: 7.0)
        map.controller.setCenter(GeoPoint(sLat ?: 11.1271, sLon ?: 78.6569))
        if (sRot != null) map.setMapOrientation(sRot)
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // two-finger rotate
        val rot = RotationGestureOverlay(map)
        rot.isEnabled = true
        map.overlays.add(rot)
        // long-press = mark a place
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) placeMark(p)
                return true
            }
        }))

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
        row.gravity = Gravity.CENTER_VERTICAL
        val menuBtn = smallBtn("\u2630")
        row.addView(menuBtn, LinearLayout.LayoutParams(-2, -2))
        input = EditText(this)
        input.hint = "Search destination"
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val backBtn = smallBtn("Back")
        row.addView(backBtn, LinearLayout.LayoutParams(-2, -2))
        val go = smallBtn("Go")
        row.addView(go, LinearLayout.LayoutParams(-2, -2))
        panel.addView(row)

        status = TextView(this)
        status.setTextColor(Color.DKGRAY)
        status.visibility = View.GONE
        panel.addView(status)

        list = ListView(this)
        list.visibility = View.GONE
        panel.addView(list, LinearLayout.LayoutParams(-1, dp(280)))

        // ---- Settings (opens with the 3-line button) ----
        settings = LinearLayout(this)
        settings.orientation = LinearLayout.VERTICAL
        settings.setPadding(dp(6), dp(6), dp(6), dp(6))

        sizeLabel = TextView(this)
        sizeLabel.setTextColor(Color.BLACK)
        sizeLabel.textSize = 15f
        sizeLabel.text = "Text size: " + (100 + sizePct) + "%"
        settings.addView(sizeLabel)

        val seek = SeekBar(this)
        seek.max = 120
        seek.progress = sizePct
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                sizePct = p
                sizeLabel.text = "Text size: " + (100 + p) + "%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("size", sizePct).apply()
                map.setTilesScaleFactor(if (themes[themeIdx].sat) 1f else factor())
                map.invalidate()
            }
        })
        settings.addView(seek)

        val moveTitle = TextView(this)
        moveTitle.text = "Map movement"
        moveTitle.setTextColor(Color.BLACK)
        moveTitle.textSize = 15f
        moveTitle.setPadding(0, dp(8), 0, 0)
        settings.addView(moveTitle)
        val moveGroup = RadioGroup(this)
        val mA = RadioButton(this)
        mA.id = View.generateViewId()
        mA.text = "A) Move only for a new popup"
        mA.setTextColor(Color.BLACK)
        val mB = RadioButton(this)
        mB.id = View.generateViewId()
        mB.text = "B) Never move by itself"
        mB.setTextColor(Color.BLACK)
        moveGroup.addView(mA)
        moveGroup.addView(mB)
        if (moveMode == 1) mB.isChecked = true else mA.isChecked = true
        moveGroup.setOnCheckedChangeListener { _, checkedId ->
            moveMode = if (checkedId == mB.id) 1 else 0
            prefs.edit().putInt("move", moveMode).apply()
        }
        settings.addView(moveGroup)

        val themeTitle = TextView(this)
        themeTitle.text = "Map theme"
        themeTitle.setTextColor(Color.BLACK)
        themeTitle.textSize = 15f
        themeTitle.setPadding(0, dp(8), 0, 0)
        settings.addView(themeTitle)

        val group = RadioGroup(this)
        val ids = ArrayList<Int>()
        for ((i, t) in themes.withIndex()) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = t.name
            rb.setTextColor(Color.BLACK)
            group.addView(rb)
            ids.add(rb.id)
            if (i == themeIdx) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i >= 0 && i != themeIdx) {
                themeIdx = i
                prefs.edit().putInt("theme", i).apply()
                applyTheme()
            }
        }
        settings.addView(group)

        settings.addView(colorRow("My route / mark color", manualColor) { c ->
            manualColor = c
            prefs.edit().putInt("mcolor", c).apply()
            manualLine?.outlinePaint?.color = c
            manualMarker?.icon = pinIcon(c)
            map.invalidate()
        })
        settings.addView(colorRow("Popup route color", popupColor) { c ->
            popupColor = c
            prefs.edit().putInt("pcolor", c).apply()
            autoLine?.outlinePaint?.color = c
            autoMarker?.icon = pinIcon(c)
            map.invalidate()
        })

        val closeBtn = smallBtn("Close")
        settings.addView(closeBtn)
        settingsBox = ScrollView(this)
        settingsBox.visibility = View.GONE
        settingsBox.addView(settings)
        panel.addView(settingsBox, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt()))

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

        // ---- Zoom + / - and north (compass) buttons ----
        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        val zin = smallBtn("+")
        val zout = smallBtn("\u2212")
        val north = smallBtn("N")
        zin.textSize = 20f
        zout.textSize = 20f
        for (b in listOf(zin, zout, north)) {
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.setMargins(0, 0, 0, dp(6))
            b.setPadding(0, 0, 0, 0)
            side.addView(b, lp)
        }
        val sLp = FrameLayout.LayoutParams(-2, -2)
        sLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        sLp.setMargins(0, 0, dp(10), 0)
        root.addView(side, sLp)
        zin.setOnClickListener { map.controller.zoomIn() }
        zout.setOnClickListener { map.controller.zoomOut() }
        north.setOnClickListener {
            map.setMapOrientation(0f)
            map.invalidate()
        }

        // ---- Close mark button ----
        closeMarkBtn = smallBtn("\u2715 Close mark")
        closeMarkBtn.visibility = View.GONE
        val cmLp = FrameLayout.LayoutParams(-2, -2)
        cmLp.gravity = Gravity.BOTTOM or Gravity.END
        cmLp.setMargins(0, 0, dp(14), dp(84))
        root.addView(closeMarkBtn, cmLp)
        closeMarkBtn.setOnClickListener { clearManual() }

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
        creditView = credit
        credit.textSize = 9f
        credit.setTextColor(Color.DKGRAY)
        credit.setShadowLayer(3f, 0f, 0f, Color.WHITE)
        val cLp = FrameLayout.LayoutParams(-2, -2)
        cLp.gravity = Gravity.BOTTOM or Gravity.START
        cLp.setMargins(dp(4), 0, 0, dp(2))
        root.addView(credit, cLp)

        setContentView(root)
        applyTheme()

        go.setOnClickListener { doSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(input.text.toString()); true
            } else false
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in results.indices) showManual(results[pos], true)
        }
        locBtn.setOnClickListener { goToMyLocation() }
        backBtn.setOnClickListener { clearAll() }
        menuBtn.setOnClickListener {
            settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (settingsBox.visibility == View.VISIBLE) list.visibility = View.GONE
        }
        closeBtn.setOnClickListener { settingsBox.visibility = View.GONE }
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
                if (!hasSaved && manualMarker == null && autoMarker == null) {
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

    private fun smallBtn(text: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(10), 0, dp(10), 0)
        return b
    }

    // Back button: clears MY search / mark only (popup result is not touched)
    private fun clearAll() {
        clearManual()
        if (!autoActive) input.setText("")
        typedText = ""
        list.visibility = View.GONE
        status.visibility = View.GONE
        settingsBox.visibility = View.GONE
        results.clear()
        if (!autoActive) {
            val me = myPoint()
            if (me != null) {
                map.controller.setZoom(16.0)
                map.controller.animateTo(me)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsBox.visibility == View.VISIBLE) {
            settingsBox.visibility = View.GONE
        } else if (manualMarker != null || (!autoActive && input.text.isNotEmpty()) || list.visibility == View.VISIBLE) {
            clearAll()
        } else {
            super.onBackPressed()
        }
    }

    private fun pinIcon(color: Int): BitmapDrawable {
        val w = dp(30)
        val h = dp(42)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w / 2f - 3f
        val path = Path()
        path.addCircle(w / 2f, w / 2f, r, Path.Direction.CW)
        val tri = Path()
        tri.moveTo(w * 0.15f, w * 0.68f)
        tri.lineTo(w / 2f, h - 2f)
        tri.lineTo(w * 0.85f, w * 0.68f)
        tri.close()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = color
        c.drawPath(path, p)
        c.drawPath(tri, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.WHITE
        c.drawPath(path, p)
        c.drawPath(tri, p)
        p.style = Paint.Style.FILL
        c.drawCircle(w / 2f, w / 2f, w / 6f, p)
        return BitmapDrawable(resources, bmp)
    }

    private fun colorRow(title: String, current: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, 0)
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.BLACK)
        t.textSize = 15f
        box.addView(t)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val btns = ArrayList<Button>()
        var sel = current
        fun refresh() {
            for ((i, b) in btns.withIndex()) b.text = if (palette[i] == sel) "\u2713" else ""
        }
        for (c in palette) {
            val b = Button(this)
            val g = GradientDrawable()
            g.setColor(c)
            g.cornerRadius = dp(18).toFloat()
            g.setStroke(dp(2), Color.LTGRAY)
            b.background = g
            b.setTextColor(Color.WHITE)
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding(0, 0, 0, 0)
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.setMargins(0, 0, dp(8), 0)
            b.setOnClickListener {
                sel = c
                refresh()
                onPick(c)
            }
            btns.add(b)
            row.addView(b, lp)
        }
        refresh()
        box.addView(row)
        return box
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
        clearAuto()
        if (!autoActive) typedText = input.text.toString()
        autoActive = true
        list.visibility = View.GONE
        status.visibility = View.GONE
        input.setText(if (d.address.isNotEmpty()) d.address else d.area)
        val me = myPoint()
        Thread {
            val place = try { Search.find(d, me) } catch (e: Exception) { null }
            runOnUiThread {
                if (place != null && my == token) showAuto(place)
            }
        }.start()
    }

    // popup gone: only the popup pin + line close. The map does NOT move.
    override fun onPopupGone() {
        token++
        if (!autoActive) return
        autoActive = false
        clearAuto()
        input.setText(typedText)
        typedText = ""
    }

    private fun clearAuto() {
        autoMarker?.let { map.overlays.remove(it) }
        autoMarker = null
        autoLine?.let { map.overlays.remove(it) }
        autoLine = null
        map.invalidate()
    }

    private fun clearManual() {
        manualToken++
        manualMarker?.let { map.overlays.remove(it) }
        manualMarker = null
        manualLine?.let { map.overlays.remove(it) }
        manualLine = null
        closeMarkBtn.visibility = View.GONE
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

    // ---------------- Pins + routes ----------------

    private fun makeMarker(pos: GeoPoint, color: Int): Marker {
        val m = Marker(map)
        m.position = pos
        m.icon = pinIcon(color)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        return m
    }

    private fun makeLine(a: GeoPoint, b: GeoPoint, color: Int): Polyline {
        val line = Polyline()
        line.outlinePaint.color = color
        line.outlinePaint.strokeWidth = dp(5).toFloat()
        line.outlinePaint.strokeCap = Paint.Cap.ROUND
        line.setPoints(listOf(a, b)) // straight line first (instant)
        map.overlays.add(routeIndex(), line)
        return line
    }

    private fun fetchRoute(line: Polyline, a: GeoPoint, b: GeoPoint, valid: () -> Boolean) {
        Thread {
            val pts = try { Search.route(a, b) } catch (e: Exception) { null }
            if (pts != null) {
                runOnUiThread {
                    if (valid()) {
                        line.setPoints(pts)
                        map.invalidate()
                    }
                }
            }
        }.start()
    }

    // Popup result (own pin + line)
    private fun showAuto(p: Place) {
        clearAuto()
        val pos = GeoPoint(p.lat, p.lon)
        val m = makeMarker(pos, popupColor)
        map.overlays.add(m)
        autoMarker = m
        val me = myPoint()
        val my = token
        if (me != null) {
            val line = makeLine(me, pos, popupColor)
            autoLine = line
            fetchRoute(line, me, pos) { my == token && autoLine === line }
            if (moveMode == 0) fitBelowPopup(me, pos)
        } else if (moveMode == 0) {
            map.controller.setZoom(if (p.approx) 14.0 else 16.0)
            map.controller.animateTo(pos)
        }
        map.invalidate()
    }

    // My search result or long-press mark (own pin + line). Stays until I close it.
    private fun showManual(p: Place, moveMap: Boolean) {
        clearManual()
        val pos = GeoPoint(p.lat, p.lon)
        val m = makeMarker(pos, manualColor)
        m.title = p.title
        m.snippet = p.sub
        map.overlays.add(m)
        manualMarker = m
        closeMarkBtn.visibility = View.VISIBLE
        list.visibility = View.GONE
        val me = myPoint()
        val my = manualToken
        if (me != null) {
            val line = makeLine(me, pos, manualColor)
            manualLine = line
            fetchRoute(line, me, pos) { my == manualToken && manualLine === line }
            if (moveMap) fitAll(me, pos)
        } else if (moveMap) {
            map.controller.setZoom(16.0)
            map.controller.animateTo(pos)
        }
        if (moveMap) m.showInfoWindow()
        map.invalidate()
    }

    private fun placeMark(g: GeoPoint) {
        showManual(Place("Marked place", "", g.latitude, g.longitude), false)
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
        try {
            val c = map.mapCenter
            getSharedPreferences("pm", Context.MODE_PRIVATE).edit()
                .putString("lat", c.latitude.toString())
                .putString("lon", c.longitude.toString())
                .putString("zoom", map.zoomLevelDouble.toString())
                .putString("rot", map.mapOrientation.toString())
                .apply()
        } catch (e: Exception) { }
        myLoc.disableMyLocation()
        map.onPause()
    }
}
