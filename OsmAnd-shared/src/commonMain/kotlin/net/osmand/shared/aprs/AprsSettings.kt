package net.osmand.shared.aprs

data class AprsEndpoint(val host: String, val port: Int = 14580) {
    companion object {
        /** Plain TCP endpoints only: a host, host:port, tcp://host:port or aprs://host:port. */
        fun parse(address: String, defaultPort: Int = 14580): AprsEndpoint {
            val value = address.trim()
            val match = Regex("^(?:(?:tcp|aprs)://)?(\\[[0-9a-fA-F:]+\\]|[a-zA-Z0-9][a-zA-Z0-9.-]*)(?::([0-9]{1,5}))?/?$",
                RegexOption.IGNORE_CASE).matchEntire(value) ?: throw IllegalArgumentException("Invalid TCP endpoint")
            val host = match.groupValues[1].removePrefix("[").removeSuffix("]").lowercase()
            if (match.groupValues[1].startsWith('[')) require(':' in host)
            val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: defaultPort
            require(port in 1..65535 && host.length <= 253)
            if (':' !in host) require(host.split('.').all {
                it.isNotEmpty() && it.length <= 63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit()
            })
            else require(host.count { it == ':' } >= 2)
            return AprsEndpoint(host, port)
        }
    }
}

object AprsIdentity {
    /** SSID zero is represented without a suffix on APRS-IS. */
    fun callsign(baseCallsign: String, ssid: Int): String {
        val base = baseCallsign.trim().uppercase()
        require(ssid in 0..15 && '-' !in base && AprsProtocol.validCallsign(base))
        return if (ssid == 0) base else "$base-$ssid"
    }
}
