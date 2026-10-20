package net.osmand.shared.aprs

import kotlin.math.abs
import kotlin.math.roundToInt

data class AprsFix(val latitude: Double, val longitude: Double, val timeMillis: Long,
                   val accuracyMeters: Double = 0.0) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
    }
}

data class AprsStation(val callsign: String, val latitude: Double, val longitude: Double,
                       val symbolTable: Char, val symbol: Char, val comment: String,
                       val heardAtMillis: Long)

/** Wire-format code independent of Android, sockets, and device clocks. */
object AprsProtocol {
    private val callPattern = Regex("[A-Z0-9]{3,6}(-[1-9A-Z][0-9A-Z]?)?")
    fun validCallsign(value: String): Boolean = value.length <= 9 && callPattern.matches(value)

    fun login(callsign: String, passcode: String, filter: String): String {
        require(validCallsign(callsign))
        require(passcode == "-1" || passcode.matches(Regex("[0-9]{1,5}")))
        require(filter.length <= 160 && filter.all { it.code in 32..126 })
        return "user $callsign pass $passcode vers OsmAndAPRS 0.1 filter $filter\r\n"
    }

    fun rangeFilter(latitude: Double, longitude: Double, radiusKm: Int): String {
        AprsFix(latitude, longitude, 0)
        require(radiusKm in 1..500)
        return "r/$latitude/$longitude/$radiusKm"
    }

    fun verifiedLogin(line: String, callsign: String): Boolean =
        Regex("^#\\s*logresp\\s+${Regex.escape(callsign)}\\s+verified(?:,|\\s|$)",
            RegexOption.IGNORE_CASE).containsMatchIn(line)

    fun beacon(callsign: String, fix: AprsFix, comment: String = "OsmAnd APRS",
               symbolTable: Char = '/', symbol: Char = '>'): String {
        require(validCallsign(callsign))
        require(symbolTable == '/' || symbolTable == '\\' || symbolTable in 'A'..'Z' || symbolTable in '0'..'9')
        require(symbol.code in 33..126)
        require(comment.length <= 80 && comment.all { it.code in 32..126 })
        return "$callsign>APRS,TCPIP*:!${coordinate(fix.latitude, true)}$symbolTable" +
            "${coordinate(fix.longitude, false)}$symbol$comment\r\n"
    }

    private fun coordinate(value: Double, latitude: Boolean): String {
        // Round before splitting so 59.995 minutes carries into the next degree.
        val hundredths = (abs(value) * 6000).roundToInt()
        val degrees = (hundredths / 6000).toString().padStart(if (latitude) 2 else 3, '0')
        val minutes = (hundredths % 6000 / 100).toString().padStart(2, '0')
        val fraction = (hundredths % 100).toString().padStart(2, '0')
        val hemisphere = if (latitude) { if (value < 0) 'S' else 'N' } else { if (value < 0) 'W' else 'E' }
        return "$degrees$minutes.$fraction$hemisphere"
    }

    /** Position reports: uncompressed, timestamped, compressed, objects and items.
     * Unsupported formats (including Mic-E and ambiguous coordinates) return null.
     */
    fun parse(line: String, nowMillis: Long): AprsStation? = runCatching {
        require(line.length <= 510 && line.none { it == '\r' || it == '\n' })
        val separator = line.indexOf(':')
        val headerEnd = line.indexOf('>')
        require(headerEnd > 0 && separator > headerEnd)
        var callsign = line.substring(0, headerEnd)
        require(callsign.length <= 9)
        var body = line.substring(separator + 1)
        require(body.isNotEmpty())
        body = when (body[0]) {
            '!', '=' -> body.substring(1)
            '/', '@' -> { require(body.length >= 8); body.substring(8) }
            ';' -> {
                require(body.length >= 18 && body[10] == '*')
                callsign = body.substring(1, 10).trim()
                body.substring(18)
            }
            ')' -> {
                val end = body.indexOf('!')
                require(end in 4..10)
                callsign = body.substring(1, end)
                body.substring(end + 1)
            }
            else -> return null
        }
        if (body.length >= 19 && body[7] in "NS" && body[17] in "EW") {
            require(body[4] == '.' && body[14] == '.')
            val lat = uncompressed(body.substring(0, 8), 2)
            val lon = uncompressed(body.substring(9, 18), 3)
            AprsStation(callsign, lat, lon, body[8], body[18], body.substring(19), nowMillis)
        } else {
            require(body.length >= 13 && body[0] in "/\\0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            fun base91(start: Int): Long = body.substring(start, start + 4).fold(0L) { total, c ->
                require(c.code in 33..123)
                total * 91 + c.code - 33
            }
            val lat = 90.0 - base91(1) / 380926.0
            val lon = -180.0 + base91(5) / 190463.0
            AprsFix(lat, lon, nowMillis)
            AprsStation(callsign, lat, lon, body[0], body[9], body.substring(13), nowMillis)
        }
    }.getOrNull()

    private fun uncompressed(value: String, degreesWidth: Int): Double {
        require(value.substring(0, degreesWidth).all { it in '0'..'9' })
        require(Regex("[0-9]{2}\\.[0-9]{2}").matches(value.substring(degreesWidth, value.lastIndex)))
        val degrees = value.substring(0, degreesWidth).toInt()
        val minutes = value.substring(degreesWidth, value.lastIndex).toDouble()
        require(minutes >= 0 && minutes < 60)
        val max = if (degreesWidth == 2) 90 else 180
        require(degrees <= max && (degrees < max || minutes == 0.0))
        val sign = if (value.last() in "SW") -1 else 1
        return sign * (degrees + minutes / 60)
    }
}

/** Bounded station memory; callers serialize access and publish immutable snapshots. */
class AprsStationStore(private val maxStations: Int = 2000, private val ttlMillis: Long = 1_800_000) {
    private val stations = linkedMapOf<String, AprsStation>()
    init { require(maxStations > 0 && ttlMillis > 0) }
    fun receive(line: String, nowMillis: Long) {
        expire(nowMillis)
        val station = AprsProtocol.parse(line, nowMillis) ?: return
        stations.remove(station.callsign)
        stations[station.callsign] = station
        while (stations.size > maxStations) stations.remove(stations.keys.first())
    }
    fun snapshot(nowMillis: Long): List<AprsStation> {
        expire(nowMillis)
        return stations.values.toList()
    }
    private fun expire(nowMillis: Long) {
        stations.entries.removeAll { nowMillis - it.value.heardAtMillis >= ttlMillis }
    }
}

class AprsBeaconPolicy(val intervalMillis: Long = 120_000) {
    private var lastSentMillis: Long? = null
    init { require(intervalMillis >= 60_000) }
    fun due(fix: AprsFix?, nowMillis: Long, enabled: Boolean, verified: Boolean): Boolean =
        enabled && verified && fix != null && nowMillis - fix.timeMillis in 0..30_000 &&
            fix.accuracyMeters <= 100 && (lastSentMillis == null || nowMillis - lastSentMillis!! >= intervalMillis)
    fun sent(nowMillis: Long) { lastSentMillis = nowMillis }
}
