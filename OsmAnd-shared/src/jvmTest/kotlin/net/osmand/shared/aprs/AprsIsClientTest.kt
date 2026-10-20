package net.osmand.shared.aprs

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Entirely loopback tests; never connect or transmit to the public APRS network. */
class AprsIsClientTest {
    @Test fun receivesStationsAndStops() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val login = CompletableFuture<String>()
            val peer = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("# loopback test\r\n"); writer.flush()
                    login.complete(socket.getInputStream().bufferedReader().readLine())
                    writer.write("# logresp N0CALL unverified, server TEST\r\n")
                    writer.write("N1CALL>APRS:!4903.50N/07201.75W>test\r\n"); writer.flush()
                    Thread.sleep(2500)
                }
            }
            val client = AprsIsClient()
            try {
                client.start(AprsIsConfig("N0CALL", host = "127.0.0.1", port = server.localPort,
                    latitude = 49.0, longitude = -72.0))
                val stations = withTimeout(5000) { client.stations.first { it.isNotEmpty() } }
                assertEquals("N1CALL", stations.single().callsign)
                assertTrue(login.get(1, TimeUnit.SECONDS).contains("pass -1"))
            } finally { client.stop(); peer.join(4000) }
            assertEquals(AprsConnectionState.STOPPED, client.state.value)
        }
    }

    @Test fun transmitsOnlyAfterVerifiedLogin() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val result = CompletableFuture<String>()
            val peer = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val writer = socket.getOutputStream().bufferedWriter()
                        val reader = socket.getInputStream().bufferedReader()
                        writer.write("# test\r\n"); writer.flush()
                        reader.readLine()
                        writer.write("# logresp N0CALL unverified, server TEST\r\n"); writer.flush()
                        socket.soTimeout = 1500
                        try { reader.readLine(); error("Beacon sent before verification") }
                        catch (_: SocketTimeoutException) { }
                        writer.write("# logresp N0CALL verified, server TEST\r\n"); writer.flush()
                        socket.soTimeout = 5000
                        result.complete(reader.readLine())
                    }
                } catch (error: Throwable) { result.completeExceptionally(error) }
            }
            val client = AprsIsClient()
            try {
                client.start(AprsIsConfig("N0CALL", "12345", "127.0.0.1", server.localPort,
                    0.0, 0.0, beaconEnabled = true))
                client.updateFix(AprsFix(49.0, -72.0, System.currentTimeMillis(), 5.0))
                val beacon = withContext(Dispatchers.IO) { result.get(8, TimeUnit.SECONDS) }
                assertTrue(beacon.startsWith("N0CALL>APRS,TCPIP*:!4900.00N/07200.00W>"))
            } finally { client.stop(); peer.join(4000) }
        }
    }
}
