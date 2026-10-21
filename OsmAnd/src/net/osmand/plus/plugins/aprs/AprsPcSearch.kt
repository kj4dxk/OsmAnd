package net.osmand.plus.plugins.aprs

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.data.LatLon
import net.osmand.data.PointDescription
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Native destination search through the user's Photon endpoint, without offline map data. */
object AprsPcSearch {
    private data class Place(val latitude: Double, val longitude: Double, val label: String)

    @JvmStatic fun show(activity: MapActivity) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = activity.getString(R.string.aprs_search_hint)
        }
        AlertDialog.Builder(activity).setTitle(R.string.aprs_search_title)
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.search_button) { _, _ ->
                val query = input.text.toString().trim().take(200)
                if (query.isNotEmpty()) search(activity, query)
            }.show()
    }

    private fun search(activity: MapActivity, query: String) {
        Toast.makeText(activity, R.string.aprs_search_loading, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val places = withContext(Dispatchers.IO) { fetch(query) }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (places.isEmpty()) {
                    Toast.makeText(activity, R.string.aprs_search_empty, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(activity).setTitle(R.string.aprs_search_destination)
                        .setItems(places.map { it.label }.toTypedArray()) { _, index ->
                            val place = places[index]
                            activity.app.targetPointsHelper.navigateToPoint(
                                LatLon(place.latitude, place.longitude), false, -1,
                                PointDescription(PointDescription.POINT_TYPE_LOCATION, place.label))
                            activity.mapActions.enterRoutePlanningMode(null, null)
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, R.string.aprs_search_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetch(query: String): List<Place> {
        val address = AprsLocalServices.BASE_URL + "/api/search?limit=10&q=" + URLEncoder.encode(query, "UTF-8")
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK)
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val features = json.getJSONArray("features")
            return (0 until features.length()).mapNotNull { index ->
                val feature = features.getJSONObject(index)
                val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
                if (geometry.optString("type") != "Point") return@mapNotNull null
                val coordinates = geometry.getJSONArray("coordinates")
                val lon = coordinates.getDouble(0)
                val lat = coordinates.getDouble(1)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                val properties = feature.getJSONObject("properties")
                val label = listOf("name", "housenumber", "street", "city", "state", "country")
                    .map { properties.optString(it, "") }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                Place(lat, lon, label.ifBlank { "$lat, $lon" })
            }
        } finally {
            connection.disconnect()
        }
    }
}
