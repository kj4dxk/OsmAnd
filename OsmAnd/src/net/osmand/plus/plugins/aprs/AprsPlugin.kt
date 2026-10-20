package net.osmand.plus.plugins.aprs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import net.osmand.Location
import net.osmand.data.RotatedTileBox
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem
import net.osmand.shared.aprs.*
import kotlin.math.hypot

/** First Android integration: explicitly enabled, foreground APRS-IS operation. */
class AprsPlugin(app: OsmandApplication) : OsmandPlugin(app) {
    private val preferences = app.getSharedPreferences("aprs", Context.MODE_PRIVATE)
    private val client = AprsIsClient()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var layer: AprsLayer? = null
    private var foreground = false

    override fun getId() = "osmand.aprs"
    override fun getName(): String = app.getString(R.string.aprs_name)
    override fun getDescription(linksEnabled: Boolean): CharSequence = app.getString(R.string.aprs_description)
    override fun getLogoResourceId() = R.drawable.ic_action_sensor

    override fun registerLayers(context: Context, mapActivity: MapActivity?) {
        val map = app.osmandMap.mapView
        val activeLayer = layer ?: AprsLayer(context).also { layer = it }
        if (isActive && !map.layers.contains(activeLayer)) map.addLayer(activeLayer, 6f)
    }

    override fun updateLayers(context: Context, mapActivity: MapActivity?) {
        if (isActive) registerLayers(context, mapActivity)
        else layer?.let { app.osmandMap.mapView.removeLayer(it) }
    }

    override fun mapActivityResume(activity: MapActivity) {
        foreground = true
        if (preferences.getBoolean("connected", false)) connect(activity)
        refreshJob?.cancel()
        refreshJob = uiScope.launch {
            client.stations.collect { layer?.stations = it; activity.mapView.refreshMap() }
        }
    }

    override fun mapActivityPause(activity: MapActivity) {
        foreground = false
        client.stop()
        refreshJob?.cancel()
    }

    override fun disable(app: OsmandApplication) {
        preferences.edit().putBoolean("connected", false).apply()
        client.stop()
        refreshJob?.cancel()
        layer?.let { app.osmandMap.mapView.removeLayer(it) }
        super.disable(app)
    }

    override fun updateLocation(location: Location?) {
        if (!foreground || location == null || !location.hasAccuracy()) return
        runCatching {
            client.updateFix(AprsFix(location.latitude, location.longitude, location.time, location.accuracy.toDouble()))
        }
    }

    override fun registerOptionsMenuItems(activity: MapActivity, helper: ContextMenuAdapter) {
        if (!isActive) return
        helper.addItem(ContextMenuItem("aprs.settings").setTitleId(R.string.aprs_name, activity)
            .setIcon(logoResourceId).setListener { _, _, _, _ -> showSettings(activity); true })
    }

    private fun connect(activity: MapActivity) {
        try {
            refreshJob?.cancel()
            refreshJob = uiScope.launch {
                client.stations.collect { layer?.stations = it; activity.mapView.refreshMap() }
            }
            val box = activity.mapView.currentRotatedTileBox
            client.start(AprsIsConfig(
                callsign = preferences.getString("callsign", "")!!.trim().uppercase(),
                passcode = preferences.getString("passcode", "-1")!!.ifBlank { "-1" },
                host = preferences.getString("host", "rotate.aprs2.net")!!.trim(),
                port = preferences.getInt("port", 14580),
                latitude = box.latitude, longitude = box.longitude,
                beaconEnabled = preferences.getBoolean("beacon", false)))
            app.locationProvider.lastKnownLocation?.let { updateLocation(it) }
        } catch (_: IllegalArgumentException) {
            preferences.edit().putBoolean("connected", false).apply()
            Toast.makeText(activity, R.string.aprs_invalid_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings(activity: MapActivity) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val status = TextView(activity).apply { text = app.getString(R.string.aprs_state, client.state.value.name, client.stations.value.size) }
        content.addView(status)
        fun field(label: Int, key: String, default: String, type: Int): EditText {
            content.addView(TextView(activity).apply { setText(label) })
            return EditText(activity).apply {
                inputType = type
                setSingleLine(true)
                setText(preferences.getString(key, default))
                content.addView(this)
            }
        }
        val callsign = field(R.string.aprs_callsign, "callsign", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val passcode = field(R.string.aprs_passcode, "passcode", "-1", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val host = field(R.string.aprs_host, "host", "rotate.aprs2.net", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        content.addView(TextView(activity).apply { setText(R.string.aprs_port) })
        val port = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(preferences.getInt("port", 14580).toString())
        }
        content.addView(port)
        val beacon = CheckBox(activity).apply {
            setText(R.string.aprs_beacon)
            isChecked = preferences.getBoolean("beacon", false)
        }
        content.addView(beacon)
        content.addView(TextView(activity).apply { setText(R.string.aprs_foreground_notice) })
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.aprs_name)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.aprs_connect, null)
            .setNegativeButton(R.string.aprs_disconnect) { _, _ ->
                preferences.edit().putBoolean("connected", false).apply()
                client.stop()
            }.setNeutralButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val portValue = port.text.toString().toIntOrNull()
                val call = callsign.text.toString().trim().uppercase()
                val code = passcode.text.toString().ifBlank { "-1" }
                val valid = runCatching {
                    AprsIsConfig(call, code, host.text.toString().trim(), portValue ?: 0,
                        0.0, 0.0, beaconEnabled = beacon.isChecked)
                }.isSuccess
                if (!valid) Toast.makeText(activity, R.string.aprs_invalid_settings, Toast.LENGTH_LONG).show()
                else {
                    preferences.edit().putString("callsign", call).putString("passcode", code)
                        .putString("host", host.text.toString().trim()).putInt("port", portValue!!)
                        .putBoolean("beacon", beacon.isChecked).putBoolean("connected", true).apply()
                    foreground = true
                    connect(activity)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        val statusJob = uiScope.launch {
            while (isActive) {
                status.text = app.getString(R.string.aprs_state, client.state.value.name, client.stations.value.size)
                delay(1000)
            }
        }
        dialog.setOnDismissListener { statusJob.cancel() }
    }

    private class AprsLayer(context: Context) : OsmandMapLayer(context) {
        var stations: List<AprsStation> = emptyList()
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 105, 170) }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        override fun drawInScreenPixels() = true
        override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
            val density = context.resources.displayMetrics.density
            labelPaint.textSize = 12 * density
            for (station in stations) {
                val p = NativeUtilities.getPixelFromLatLon(mapRenderer, tileBox, station.latitude, station.longitude)
                if (p.x < 0 || p.y < 0 || p.x > tileBox.pixWidth || p.y > tileBox.pixHeight) continue
                canvas.drawCircle(p.x, p.y, 7 * density, markerPaint)
                canvas.drawText(station.callsign, p.x + 10 * density, p.y, labelPaint)
            }
        }
        override fun onSingleTap(point: PointF, tileBox: RotatedTileBox): Boolean {
            val radius = 24 * context.resources.displayMetrics.density
            val station = stations.firstOrNull {
                val p = NativeUtilities.getPixelFromLatLon(mapRenderer, tileBox, it.latitude, it.longitude)
                hypot((p.x - point.x).toDouble(), (p.y - point.y).toDouble()) <= radius
            } ?: return false
            val activity = mapActivity ?: return false
            AlertDialog.Builder(activity).setTitle(station.callsign)
                .setMessage(activity.getString(R.string.aprs_station_details, station.latitude,
                    station.longitude, station.symbolTable.toString() + station.symbol,
                    ((System.currentTimeMillis() - station.heardAtMillis) / 1000).toInt(), station.comment))
                .setPositiveButton(android.R.string.ok, null).show()
            return true
        }
    }
}
