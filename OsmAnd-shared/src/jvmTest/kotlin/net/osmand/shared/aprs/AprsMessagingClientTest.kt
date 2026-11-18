package net.osmand.shared.aprs

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AprsMessagingClientTest {
    @Test fun verifiedMessagesAndAcknowledgementsUseSameConnection(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8000
            val outgoing = CompletableFuture<String>()
            val ack = CompletableFuture<String>()
            val peer = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 8000
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("# loopback\r\n"); writer.flush()
                        assertTrue(reader.readLine().contains("g/N0CALL-12"))
                        writer.write("# logresp N0CALL-12 verified, server TEST\r\n"); writer.flush()
                        outgoing.complete(reader.readLine())
                        writer.write("N1CALL-7>APRS::N0CALL-12:Hello back{77\r\n"); writer.flush()
                        ack.complete(reader.readLine())
                    }
                } catch(e: Throwable) { outgoing.completeExceptionally(e); ack.completeExceptionally(e) }
            }
            val client = AprsIsClient()
            try {
                val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(8000) { client.messages.first() } }
                client.start(AprsIsConfig("N0CALL-12", "12345", "127.0.0.1", server.localPort, 0.0, 0.0))
                withTimeout(8000) { client.state.first { it == AprsConnectionState.VERIFIED } }
                client.sendMessage("N0CALL-12", "N1CALL-7", "Hello", "42")
                assertEquals("N0CALL-12>APRS,TCPIP*::N1CALL-7 :Hello{42", outgoing.get(3, TimeUnit.SECONDS))
                val message = received.await()
                assertEquals("77", message.id)
                client.acknowledge("N0CALL-12", message.sender, message.id!!)
                assertEquals("N0CALL-12>APRS,TCPIP*::N1CALL-7 :ack77", ack.get(3, TimeUnit.SECONDS))
            } finally { client.stop(); peer.join(9000) }
            assertFailsWith<IllegalStateException> { client.sendMessage("N0CALL-12", "N1CALL", "No connection", "1") }
        }
    }
}
