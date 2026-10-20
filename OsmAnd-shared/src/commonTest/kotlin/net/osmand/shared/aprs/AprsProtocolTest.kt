package net.osmand.shared.aprs

import kotlin.test.*

class AprsProtocolTest {
    private val packet = "N0CALL-9>APRS,TCPIP*:!4903.50N/07201.75W>mobile"
    @Test fun uncompressedPosition() {
        val station = assertNotNull(AprsProtocol.parse(packet, 100))
        assertEquals("N0CALL-9", station.callsign)
        assertEquals(49.058333333, station.latitude, 0.000001)
        assertEquals(-72.029166667, station.longitude, 0.000001)
        assertEquals('>', station.symbol)
        assertEquals("mobile", station.comment)
    }
    @Test fun timestampedPosition() {
        assertNotNull(AprsProtocol.parse(packet.replace("!", "@201230z"), 100))
    }
    @Test fun objectPosition() {
        val objectPacket = packet.replace("!", ";TESTOBJ  *201230z")
        assertEquals("TESTOBJ", assertNotNull(AprsProtocol.parse(objectPacket, 100)).callsign)
    }
    @Test fun compressedPosition() {
        fun encode(value: Long): String {
            var n = value
            val chars = CharArray(4)
            for (i in 3 downTo 0) { chars[i] = (33 + n % 91).toInt().toChar(); n /= 91 }
            return chars.concatToString()
        }
        val body = "/" + encode((90 * 380926).toLong()) + encode((180 * 190463).toLong()) + ">!!!"
        val station = assertNotNull(AprsProtocol.parse("N0CALL>APRS:!$body", 0))
        assertEquals(0.0, station.latitude, 0.00001)
        assertEquals(0.0, station.longitude, 0.00001)
    }
    @Test fun malformedPacketsAreIgnored() {
        for (line in listOf("", "# server", "N0CALL>APRS:>status", packet.replace("03.50", "63.50"),
            packet.replace("4903.50N", "9103.50N"), packet.replace("03.50", " 3.5 "), packet + "\n")) {
            assertNull(AprsProtocol.parse(line, 0), line)
        }
    }
    @Test fun beaconWireFormat() {
        assertEquals("N0CALL-9>APRS,TCPIP*:!4903.50N/07201.75W>OsmAnd APRS\r\n",
            AprsProtocol.beacon("N0CALL-9", AprsFix(49 + 3.5 / 60, -(72 + 1.75 / 60), 0)))
    }
    @Test fun minuteCarryAndPoles() {
        assertTrue(AprsProtocol.beacon("N0CALL", AprsFix(12.9999999, -179.9999999, 0))
            .contains("1300.00N/18000.00W"))
        assertTrue(AprsProtocol.beacon("N0CALL", AprsFix(-90.0, 180.0, 0)).contains("9000.00S/18000.00E"))
    }
    @Test fun rejectInjectionAndBadCoordinates() {
        assertFailsWith<IllegalArgumentException> { AprsProtocol.login("N0CALL\r\n", "-1", "r/0/0/10") }
        assertFailsWith<IllegalArgumentException> { AprsProtocol.login("N0CALL", "-1", "r/0/0/10\n") }
        assertFailsWith<IllegalArgumentException> { AprsProtocol.beacon("N0CALL", AprsFix(0.0, 0.0, 0), "hello\r\n") }
        assertFailsWith<IllegalArgumentException> { AprsFix(Double.NaN, 0.0, 0) }
    }
    @Test fun serverVerificationIsExact() {
        assertTrue(AprsProtocol.verifiedLogin("# logresp N0CALL-9 verified, server TEST", "N0CALL-9"))
        assertFalse(AprsProtocol.verifiedLogin("# logresp N0CALL-9 unverified, server TEST", "N0CALL-9"))
        assertFalse(AprsProtocol.verifiedLogin("# logresp OTHER verified, server TEST", "N0CALL-9"))
    }
    @Test fun beaconRequiresFreshAccurateFixAndOptIn() {
        val policy = AprsBeaconPolicy()
        val fix = AprsFix(0.0, 0.0, 100_000)
        assertFalse(policy.due(fix, 100_000, false, true))
        assertFalse(policy.due(fix, 100_000, true, false))
        assertFalse(policy.due(fix, 131_000, true, true))
        assertFalse(policy.due(fix.copy(accuracyMeters = 101.0), 100_000, true, true))
        assertTrue(policy.due(fix, 100_000, true, true))
        policy.sent(100_000)
        assertFalse(policy.due(fix.copy(timeMillis = 200_000), 200_000, true, true))
        assertTrue(policy.due(fix.copy(timeMillis = 220_000), 220_000, true, true))
    }
    @Test fun stationStoreIsBoundedAndExpires() {
        val store = AprsStationStore(1, 1000)
        store.receive(packet, 0)
        store.receive(packet.replace("N0CALL", "N1CALL"), 100)
        assertEquals("N1CALL-9", store.snapshot(100).single().callsign)
        assertTrue(store.snapshot(1100).isEmpty())
    }
    @Test fun receiveOnlyConfigurationCannotBeacon() {
        assertFailsWith<IllegalArgumentException> {
            AprsIsConfig("N0CALL", latitude = 0.0, longitude = 0.0, beaconEnabled = true)
        }
        assertFalse(AprsIsConfig("N0CALL", "12345", latitude = 0.0, longitude = 0.0).toString().contains("12345"))
    }
}
