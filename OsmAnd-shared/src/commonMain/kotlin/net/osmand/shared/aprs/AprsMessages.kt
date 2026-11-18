package net.osmand.shared.aprs

/** APRS 1.0 messages, explicit ACK/REJ, and APRS 1.1 reply-ack suffixes. */
data class AprsMessage(val sender: String, val recipient: String, val text: String,
                       val id: String? = null, val acknowledgement: String? = null,
                       val rejected: Boolean = false, val replyAck: String? = null)

object AprsMessages {
    private val idPattern = Regex("[A-Za-z0-9]{1,5}")
    fun validText(text: String) = text.isNotBlank() && text.length <= 67 &&
        text.all { it.code in 32..126 && it !in "|~{" }

    fun packet(sender: String, recipient: String, text: String, id: String): String {
        require(validText(text) && idPattern.matches(id))
        return envelope(sender, recipient, "$text{$id")
    }

    fun ack(sender: String, recipient: String, id: String): String {
        require(idPattern.matches(id))
        return envelope(sender, recipient, "ack$id")
    }

    private fun envelope(sender: String, recipient: String, payload: String): String {
        require(AprsProtocol.validCallsign(sender) && AprsProtocol.validCallsign(recipient))
        return "$sender>APRS,TCPIP*::${recipient.padEnd(9)}:$payload\r\n"
    }

    fun parse(packet: String): AprsMessage? = runCatching {
        require(packet.length <= 510 && packet.none { it == '\r' || it == '\n' })
        val split = packet.indexOf(':')
        val sender = packet.substringBefore('>')
        require(AprsProtocol.validCallsign(sender))
        val body = packet.substring(split + 1)
        require(split > packet.indexOf('>') && body.length >= 11 && body[0] == ':' && body[10] == ':')
        val recipient = body.substring(1, 10).trimEnd()
        require(AprsProtocol.validCallsign(recipient))
        val payload = body.substring(11)
        val response = Regex("^(ack|rej)([A-Za-z0-9]{1,5})(?:\\}([A-Za-z0-9]{0,5}))?$").matchEntire(payload)
        if (response != null) return AprsMessage(sender, recipient, "", acknowledgement = response.groupValues[2],
            rejected = response.groupValues[1] == "rej", replyAck = response.groupValues[3].ifEmpty { null })
        val suffix = payload.indexOf('{')
        val text = if (suffix < 0) payload else payload.substring(0, suffix)
        require(validText(text))
        var id: String? = null
        var reply: String? = null
        if (suffix >= 0) {
            val ids = payload.substring(suffix + 1).split('}')
            require(ids.size <= 2 && idPattern.matches(ids[0]))
            id = ids[0]
            reply = ids.getOrNull(1)?.ifEmpty { null }
            require(reply == null || idPattern.matches(reply))
        }
        AprsMessage(sender, recipient, text, id, replyAck = reply)
    }.getOrNull()
}

/** Never display a stale fix as a current speed. Android and future iOS share conversions. */
object AprsSpeed {
    fun value(metersPerSecond: Double?, ageMillis: Long, metric: Boolean): Int? {
        if (metersPerSecond == null || !metersPerSecond.isFinite() || metersPerSecond < 0 || ageMillis !in 0..15_000) return null
        return kotlin.math.round(metersPerSecond * if (metric) 3.6 else 2.2369362921).toInt()
    }
}
