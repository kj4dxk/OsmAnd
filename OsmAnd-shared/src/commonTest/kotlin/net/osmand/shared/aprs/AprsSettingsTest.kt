package net.osmand.shared.aprs

import kotlin.test.*

class AprsSettingsTest {
    @Test fun callsignAndSsid() {
        assertEquals("KJ4DXK-9", AprsIdentity.callsign(" kj4dxk ", 9))
        assertEquals("KJ4DXK", AprsIdentity.callsign("KJ4DXK", 0))
        assertEquals("KJ4DXK-15", AprsIdentity.callsign("KJ4DXK", 15))
        for (ssid in listOf(-1, 16)) assertFailsWith<IllegalArgumentException> { AprsIdentity.callsign("KJ4DXK", ssid) }
        assertFailsWith<IllegalArgumentException> { AprsIdentity.callsign("KJ4DXK-9", 9) }
    }
    @Test fun customEndpoints() {
        assertEquals(AprsEndpoint("rotate.aprs2.net"), AprsEndpoint.parse("rotate.aprs2.net"))
        assertEquals(AprsEndpoint("example.org", 12345), AprsEndpoint.parse("tcp://EXAMPLE.org:12345/"))
        assertEquals(AprsEndpoint("127.0.0.1", 34580), AprsEndpoint.parse("127.0.0.1:34580"))
        assertEquals(AprsEndpoint("::1", 14580), AprsEndpoint.parse("aprs://[::1]:14580"))
    }
    @Test fun rejectUnsupportedAndMalformedUrls() {
        for (address in listOf("", "https://example.org", "wss://example.org", "tcp://user:pass@example.org",
            "example.org/path", "example.org?x=1", "example.org:0", "example.org:65536", "example..org",
            "tcp://host\r\nuser OTHER", "tcp://[abcd]:14580")) {
            assertFailsWith<IllegalArgumentException>(address) { AprsEndpoint.parse(address) }
        }
    }
}
