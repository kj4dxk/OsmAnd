package net.osmand.plus.plugins.aprs

import android.content.Context
import net.osmand.map.TileSourceManager.TileSourceTemplate
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.onlinerouting.EngineParameter
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine
import net.osmand.plus.onlinerouting.engine.OsrmEngine
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.rastermaps.OsmandRasterMapsPlugin
import net.osmand.plus.routing.RouteService
import net.osmand.plus.settings.backend.ApplicationMode

/** Applied once per installation/upgrade, then user changes remain in control. */
object AprsAppDefaults {
    @JvmStatic fun applyOnce(app: OsmandApplication): Boolean {
        val prefs = app.getSharedPreferences("aprsisosm_defaults", Context.MODE_PRIVATE)
        if (prefs.getBoolean("pc_defaults_v1", false)) return true
        val settings = app.settings
        val tiles = TileSourceTemplate(app.getString(R.string.aprs_pc_map_source), TileSourceTemplate.normalizeUrl(AprsLocalServices.TILE_URL),
            ".png", 18, 0, 256, 16, 20000)
        if (!settings.installTileSource(tiles)) return false

        for (plugin in PluginsHelper.getAvailablePlugins()) {
            val enabled = plugin is AprsPlugin || plugin is OsmandRasterMapsPlugin
            PluginsHelper.enablePlugin(null, app, plugin, enabled)
        }

        settings.USE_LAST_APPLICATION_MODE_BY_DEFAULT.set(false)
        settings.DEFAULT_APPLICATION_MODE.set(ApplicationMode.CAR)
        settings.setApplicationMode(ApplicationMode.CAR, false)
        settings.SEND_ANONYMOUS_MAP_DOWNLOADS_DATA.set(false)
        settings.SEND_ANONYMOUS_APP_USAGE_DATA.set(false)
        settings.SEND_ANONYMOUS_DATA_REQUEST_PROCESSED.set(true)
        settings.SEND_UNIQUE_USER_IDENTIFIER.set(false)
        settings.SEARCH_HISTORY.set(false)
        settings.NAVIGATION_HISTORY.set(false)
        settings.MAP_MARKERS_HISTORY.set(false)
        app.poiFilters.clearAllSelectedPoiFilters()
        settings.AUTO_BACKUP_ENABLED.set(false)
        settings.DO_NOT_SHOW_STARTUP_MESSAGES.set(true)
        settings.SHOW_SUGGEST_MAP_DIALOG.set(false)
        settings.IS_LIVE_UPDATES_ON.set(false)
        settings.SPEED_CAMERAS_UNINSTALLED.set(true)
        settings.SPEED_CAMERAS_ALERT_SHOWED.set(true)
        settings.MAP_OVERLAY_PREVIOUS.set(null)
        settings.MAP_UNDERLAY_PREVIOUS.set(null)

        // PC tiles are the main map. Route calculation uses independent online engines.
        for (mode in ApplicationMode.allPossibleValues()) {
            settings.MAP_TILE_SOURCES.setModeValue(mode, tiles.name)
            settings.MAP_ONLINE_DATA.setModeValue(mode, true)
            settings.MAP_TRANSPARENCY.setModeValue(mode, 255)
            settings.MAP_OVERLAY.setModeValue(mode, null)
            settings.MAP_UNDERLAY.setModeValue(mode, null)
            settings.SHOW_CAMERAS.setModeValue(mode, false)
            settings.SPEAK_SPEED_CAMERA.setModeValue(mode, false)
            settings.SHOW_POI_LABEL.setModeValue(mode, false)
            settings.setSelectedPoiFilters(mode, emptySet())
        }
        val routes = listOf(
            Triple(ApplicationMode.CAR, "car", R.string.aprs_route_driving),
            Triple(ApplicationMode.PEDESTRIAN, "foot", R.string.aprs_route_walking),
            Triple(ApplicationMode.BICYCLE, "bike", R.string.aprs_route_cycling))
        for ((mode, vehicle, label) in routes) {
            val key = OnlineRoutingEngine.ONLINE_ROUTING_ENGINE_PREFIX + "aprsisosm_" + vehicle
            val engine = OsrmEngine(mapOf(
                EngineParameter.KEY.name to key,
                EngineParameter.VEHICLE_KEY.name to vehicle,
                EngineParameter.CUSTOM_NAME.name to app.getString(label),
                EngineParameter.CUSTOM_URL.name to AprsLocalServices.ROUTING_URL))
            app.onlineRoutingHelper.saveEngine(engine)
            settings.ROUTE_SERVICE.setModeValue(mode, RouteService.ONLINE)
            settings.ROUTING_PROFILE.setModeValue(mode, key)
            settings.VOICE_PROVIDER.resetModeToDefault(mode)
            settings.VOICE_PROVIDER.getModeValue(mode) // Resolves/copies bundled TTS for the phone's language.
            settings.VOICE_MUTE.setModeValue(mode, false)
        }
        prefs.edit().putBoolean("pc_defaults_v1", true).apply()
        return true
    }
}
