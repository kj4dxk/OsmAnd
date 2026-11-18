package net.osmand.shared.aprs

import kotlin.test.*

class AprsMessagesTest {
    @Test fun roundTripsAndPadsRecipient() {
        val packet = AprsMessages.packet("N0CALL-12", "N1CALL-7", "Hello", "A12")
        assertEquals("N0CALL-12>APRS,TCPIP*::N1CALL-7 :Hello{A12\r\n", packet)
        assertEquals(AprsMessage("N0CALL-12", "N1CALL-7", "Hello", "A12"), AprsMessages.parse(packet.trimEnd()))
    }
    @Test fun handlesAcknowledgementsRejectsAndReplyAcks() {
        assertEquals("42", AprsMessages.parse("N1CALL>APRS::N0CALL   :ack42")?.acknowledgement)
        assertEquals(true, AprsMessages.parse("N1CALL>APRS::N0CALL   :rej42")?.rejected)
        val reply = AprsMessages.parse("N1CALL>APRS::N0CALL   :Roger{AB}CD")!!
        assertEquals("AB", reply.id); assertEquals("CD", reply.replyAck)
        assertEquals("CD", AprsMessages.parse("N1CALL>APRS::N0CALL   :ackAB}CD")?.replyAck)
    }
    @Test fun rejectsInjectionUnicodeAndOverlongMessages() {
        for(text in listOf("", " ", "hello\r\nuser", "café", "a".repeat(68), "bad|", "bad~", "bad{")) {
            assertFailsWith<IllegalArgumentException> { AprsMessages.packet("N0CALL", "N1CALL", text, "1") }
        }
        assertTrue(AprsMessages.validText("x".repeat(67)))
        assertFailsWith<IllegalArgumentException> { AprsMessages.ack("N0CALL", "N1CALL", "1\r\n") }
    }
    @Test fun ignoresOtherPacketTypesAndMalformedMessageIds() {
        for(packet in listOf("N1CALL>APRS:!4903.50N/07201.75W>", "N1CALL>APRS::N0CALL:bad padding", "N1CALL>APRS::N0CALL   :hello{123456", "N1CALL>APRS::N0CALL   :hello\n")) {
            assertNull(AprsMessages.parse(packet))
        }
    }
    @Test fun speedUnitsAndStaleFixes() {
        assertEquals(36, AprsSpeed.value(10.0, 500, true))
        assertEquals(22, AprsSpeed.value(10.0, 500, false))
        assertEquals(0, AprsSpeed.value(0.0, 1000, false))
        assertNull(AprsSpeed.value(10.0, 15_001, false))
        assertNull(AprsSpeed.value(null, 1000, false))
        assertNull(AprsSpeed.value(Double.NaN, 1000, false))
        assertNull(AprsSpeed.value(-1.0, 1000, false))
        assertNull(AprsSpeed.value(10.0, -1, false))
    }
}
