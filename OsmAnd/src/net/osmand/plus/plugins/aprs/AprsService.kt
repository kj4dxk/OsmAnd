package net.osmand.plus.plugins.aprs

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.shared.aprs.*

/** Owns APRS networking and GPS independently of the map activity. */
class AprsService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locations: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusJob: Job? = null
    private val preferences by lazy { getSharedPreferences("aprs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.aprs_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    // Permission is checked by the visible activity; revocation is handled by SecurityException.
    // The service owns the wake lock until onDestroy, rather than a fixed session timeout.
    @android.annotation.SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !preferences.getBoolean("connected", false)) {
            stop(this)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            val config = AprsIsConfig(
                callsign = preferences.getString("callsign", "")!!,
                passcode = preferences.getString("passcode", "-1")!!.ifBlank { "-1" },
                host = preferences.getString("host", "rotate.aprs2.net")!!,
                port = preferences.getInt("port", 14580),
                latitude = preferences.getFloat("filter_lat", 0f).toDouble(),
                longitude = preferences.getFloat("filter_lon", 0f).toDouble(),
                beaconEnabled = preferences.getBoolean("beacon", false))
            client.start(config)
            // A partial wake lock keeps socket processing and beacon scheduling alive with the screen off.
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:APRS").apply { acquire() }
            }
            locations.removeUpdates(this)
            if (config.beaconEnabled) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
            }
            running = true
            statusJob?.cancel()
            statusJob = scope.launch {
                while (isActive) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    delay(5000)
                }
            }
        } catch (_: SecurityException) {
            failed()
        } catch (_: IllegalArgumentException) {
            failed()
        }
        // No boot or silent process-restart transmission: reconnect from the visible map after interruption.
        return START_NOT_STICKY
    }

    private fun failed() {
        Toast.makeText(this, R.string.aprs_service_failed, Toast.LENGTH_LONG).show()
        stop(this)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, AprsService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mode = if (preferences.getBoolean("beacon", false)) R.string.aprs_beacon_on else R.string.aprs_beacon_off
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_sensor)
            .setContentTitle(getString(R.string.aprs_name))
            .setContentText(getString(R.string.aprs_notification_status, client.state.value.name,
                client.stations.value.size.toString(), getString(mode)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.aprs_disconnect), disconnect).build()
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy()) return
        // Convert monotonic fix age to wall time: stale cached GPS must never become a fresh beacon.
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        runCatching { client.updateFix(AprsFix(location.latitude, location.longitude,
            System.currentTimeMillis() - ageMillis.coerceAtLeast(0), location.accuracy.toDouble())) }
    }

    @Deprecated("Legacy LocationListener callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        running = false
        locations.removeUpdates(this)
        client.stop()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        val client = AprsIsClient()
        @Volatile var running = false
            private set
        private const val CHANNEL = "aprs_connection"
        private const val NOTIFICATION_ID = 7281
        private const val STOP = "net.osmand.aprs.DISCONNECT"
        fun stop(context: Context) {
            context.getSharedPreferences("aprs", MODE_PRIVATE).edit().putBoolean("connected", false).apply()
            client.stop()
            context.stopService(Intent(context, AprsService::class.java))
        }
    }
}
