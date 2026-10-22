package net.osmand.plus.plugins.aprs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
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
import org.json.JSONObject

/** Map controls and station layer for the APRS foreground service. */
class AprsPlugin(app: OsmandApplication) : OsmandPlugin(app) {
    private val preferences = app.getSharedPreferences("aprs", Context.MODE_PRIVATE)
    private val client get() = AprsService.client
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var layer: AprsLayer? = null

    override fun getId() = "osmand.aprs"
    override fun isEnableByDefault() = true
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
        if (preferences.getBoolean("connected", false) && !AprsService.running) connect(activity)
        refreshJob?.cancel()
        refreshJob = uiScope.launch {
            client.stations.collect { layer?.stations = it; activity.mapView.refreshMap() }
        }
    }

    override fun mapActivityPause(activity: MapActivity) {
        refreshJob?.cancel()
    }

    override fun disable(app: OsmandApplication) {
        preferences.edit().putBoolean("connected", false).apply()
        AprsService.stop(app)
        refreshJob?.cancel()
        layer?.let { app.osmandMap.mapView.removeLayer(it) }
        super.disable(app)
    }

    override fun registerOptionsMenuItems(activity: MapActivity, helper: ContextMenuAdapter) {
        if (!isActive) return
        helper.addItem(ContextMenuItem("aprs.settings").setTitleId(R.string.aprs_name, activity)
            .setIcon(logoResourceId).setListener { _, _, _, _ -> showSettings(activity); true })
        helper.addItem(ContextMenuItem("aprs.services").setTitleId(R.string.aprs_services_title, activity)
            .setIcon(R.drawable.ic_action_help_online).setListener { _, _, _, _ -> AprsLocalServices.show(activity); true })
    }

    private fun connect(activity: MapActivity) {
        try {
            refreshJob?.cancel()
            refreshJob = uiScope.launch {
                client.stations.collect { layer?.stations = it; activity.mapView.refreshMap() }
            }
            val box = activity.mapView.currentRotatedTileBox
            if (androidx.core.content.ContextCompat.checkSelfPermission(activity,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                preferences.edit().putBoolean("connected", false).apply()
                androidx.core.app.ActivityCompat.requestPermissions(activity,
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7281)
                Toast.makeText(activity, R.string.aprs_permission_retry, Toast.LENGTH_LONG).show()
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(activity,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7282)
            }
            preferences.edit().putFloat("filter_lat", box.latitude.toFloat())
                .putFloat("filter_lon", box.longitude.toFloat()).apply()
            androidx.core.content.ContextCompat.startForegroundService(activity,
                android.content.Intent(activity, AprsService::class.java))
        } catch (_: SecurityException) {
            AprsService.stop(app)
            Toast.makeText(activity, R.string.aprs_service_failed, Toast.LENGTH_LONG).show()
        } catch (_: IllegalStateException) {
            AprsService.stop(app)
            Toast.makeText(activity, R.string.aprs_service_failed, Toast.LENGTH_LONG).show()
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
        val savedCallsign = preferences.getString("callsign", "")!!
        val callsign = field(R.string.aprs_callsign, "callsign", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        callsign.setText(savedCallsign.substringBefore('-'))
        content.addView(TextView(activity).apply { setText(R.string.aprs_ssid) })
        val ssid = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                (0..15).map { if (it == 0) app.getString(R.string.aprs_ssid_zero) else "-$it" })
            setSelection(savedCallsign.substringAfter('-', "0").toIntOrNull()?.coerceIn(0, 15) ?: 0)
        }
        content.addView(ssid)
        val passcode = field(R.string.aprs_passcode, "passcode", "-1", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passcode.transformationMethod = PasswordTransformationMethod.getInstance()
        val directory = JSONObject(app.assets.open("aprs_servers.json").bufferedReader().use { it.readText() })
        val directoryServers = directory.getJSONArray("servers")
        val presets = (0 until directoryServers.length()).map { directoryServers.getJSONObject(it) }
        content.addView(TextView(activity).apply { setText(R.string.aprs_server_preset) })
        val savedHost = preferences.getString("host", "rotate.aprs2.net")!!
        val savedPort = preferences.getInt("port", 14580)
        val selectedPreset = presets.indexOfFirst { it.getString("host") == savedHost && it.getInt("port") == savedPort }
        val server = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.getString("name") } + app.getString(R.string.aprs_custom_server))
            setSelection(if (selectedPreset >= 0) selectedPreset else presets.size)
        }
        content.addView(server)
        val custom = field(R.string.aprs_custom_url, "custom_server", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        if (selectedPreset < 0 && custom.text.isBlank()) {
            val formattedHost = if (':' in savedHost) "[$savedHost]" else savedHost
            custom.setText("tcp://$formattedHost:$savedPort")
        }
        custom.hint = app.getString(R.string.aprs_custom_url_hint)
        content.addView(TextView(activity).apply { setText(R.string.aprs_port) })
        val port = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(preferences.getInt("port", 14580).toString())
        }
        content.addView(port)
        server.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                custom.isEnabled = position == presets.size
                if (position < presets.size) port.setText(presets[position].getInt("port").toString())
            }
        }
        custom.isEnabled = selectedPreset < 0
        content.addView(TextView(activity).apply {
            text = app.getString(R.string.aprs_server_directory_note, directory.getString("checkedOn"))
        })
        val beacon = CheckBox(activity).apply {
            setText(R.string.aprs_beacon)
            isChecked = preferences.getBoolean("beacon", false)
        }
        content.addView(beacon)
        content.addView(TextView(activity).apply { setText(R.string.aprs_foreground_notice) })
        fun saveSettings(connectNow: Boolean): Boolean {
            val result = runCatching {
                val call = AprsIdentity.callsign(callsign.text.toString(), ssid.selectedItemPosition)
                val code = passcode.text.toString().trim().ifBlank { "-1" }
                val portValue = port.text.toString().toIntOrNull() ?: 0
                val endpoint = if (server.selectedItemPosition < presets.size) {
                    AprsEndpoint.parse(presets[server.selectedItemPosition].getString("host"), portValue)
                } else AprsEndpoint.parse(custom.text.toString(), portValue)
                AprsIsConfig(call, code, endpoint.host, endpoint.port, 0.0, 0.0, beaconEnabled = beacon.isChecked)
                preferences.edit().putString("callsign", call).putString("passcode", code)
                    .putString("host", endpoint.host).putInt("port", endpoint.port)
                    .putString("custom_server", custom.text.toString().trim())
                    .putBoolean("beacon", beacon.isChecked).putBoolean("connected", connectNow).apply()
            }
            if (result.isFailure) Toast.makeText(activity, R.string.aprs_invalid_settings, Toast.LENGTH_LONG).show()
            return result.isSuccess
        }
        val saveButton = Button(activity).apply { setText(R.string.aprs_save) }
        content.addView(saveButton)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.aprs_name)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.aprs_connect, null)
            .setNegativeButton(R.string.aprs_disconnect) { _, _ ->
                preferences.edit().putBoolean("connected", false).apply()
                AprsService.stop(app)
            }.setNeutralButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveSettings(true)) {
                    connect(activity)
                    dialog.dismiss()
                }
            }
        }
        saveButton.setOnClickListener {
            if (saveSettings(false)) {
                AprsService.stop(app)
                Toast.makeText(activity, R.string.aprs_settings_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
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
