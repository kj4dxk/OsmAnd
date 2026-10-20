package net.osmand.shared.aprs

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

data class AprsIsConfig(val callsign: String, val passcode: String = "-1",
                        val host: String = "rotate.aprs2.net", val port: Int = 14580,
                        val latitude: Double, val longitude: Double,
                        val radiusKm: Int = 100, val beaconEnabled: Boolean = false,
                        val beaconIntervalMillis: Long = 120_000) {
    init {
        AprsProtocol.login(callsign, passcode, AprsProtocol.rangeFilter(latitude, longitude, radiusKm))
        require(host.isNotBlank() && host.length <= 253 && host.all { it.isLetterOrDigit() || it in ".-:" })
        require(port in 1..65535)
        require(!beaconEnabled || passcode != "-1")
        require(beaconIntervalMillis >= 60_000)
    }
    // Credentials must never appear in logs or diagnostic UI.
    override fun toString() = "AprsIsConfig(callsign=$callsign, host=$host, port=$port)"
}

enum class AprsConnectionState { STOPPED, CONNECTING, RECEIVING, VERIFIED, RECONNECTING }

/** APRS-IS transport shared by Android and future iOS clients. No connection until start(). */
class AprsIsClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AprsConnectionState.STOPPED)
    val state: StateFlow<AprsConnectionState> = mutableState
    private val mutableStations = MutableStateFlow<List<AprsStation>>(emptyList())
    val stations: StateFlow<List<AprsStation>> = mutableStations
    private val fix = MutableStateFlow<AprsFix?>(null)
    private var job: Job? = null

    fun updateFix(value: AprsFix) { fix.value = value }

    fun start(config: AprsIsConfig) {
        stop()
        job = scope.launch {
            val store = AprsStationStore()
            val policy = AprsBeaconPolicy(config.beaconIntervalMillis)
            var backoff = 2_000L
            while (isActive) {
                mutableState.value = AprsConnectionState.CONNECTING
                try {
                    session(config, store, policy)
                } catch (_: TimeoutCancellationException) {
                    ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not expose a login line, passcode, or raw exception to logs.
                }
                ensureActive()
                mutableState.value = AprsConnectionState.RECONNECTING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        fix.value = null
        mutableState.value = AprsConnectionState.STOPPED
    }

    private suspend fun session(config: AprsIsConfig, store: AprsStationStore, policy: AprsBeaconPolicy) = coroutineScope {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = withTimeout(20_000) {
                aSocket(selector).tcp().connect(config.host, config.port) { noDelay = true }
            }
            try {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                val writeLock = Mutex()
                val banner = withTimeout(20_000) { input.readUTF8Line(512) }
                check(banner?.startsWith("#") == true)
                output.writeStringUtf8(AprsProtocol.login(config.callsign, config.passcode,
                    AprsProtocol.rangeFilter(config.latitude, config.longitude, config.radiusKm)))
                mutableState.value = AprsConnectionState.RECEIVING
                val verified = MutableStateFlow(false)
                val storeLock = Mutex()
                val ticker = launch {
                    while (isActive) {
                        delay(1000)
                        val now = Clock.System.now().toEpochMilliseconds()
                        mutableStations.value = storeLock.withLock { store.snapshot(now) }
                        val currentFix = fix.value
                        if (policy.due(currentFix, now, config.beaconEnabled, verified.value)) {
                            writeLock.withLock {
                                output.writeStringUtf8(AprsProtocol.beacon(config.callsign, currentFix!!))
                            }
                            policy.sent(now)
                        }
                    }
                }
                try {
                    while (isActive) {
                        val line = withTimeout(120_000) { input.readUTF8Line(512) } ?: break
                        if (line.startsWith("#")) {
                            if (line.contains("logresp", ignoreCase = true)) {
                                verified.value = AprsProtocol.verifiedLogin(line, config.callsign)
                                mutableState.value = if (verified.value) AprsConnectionState.VERIFIED else AprsConnectionState.RECEIVING
                            }
                        } else {
                            storeLock.withLock { store.receive(line, Clock.System.now().toEpochMilliseconds()) }
                        }
                    }
                } finally { ticker.cancelAndJoin() }
            } finally { socket.close() }
        } finally { selector.close() }
    }
}
