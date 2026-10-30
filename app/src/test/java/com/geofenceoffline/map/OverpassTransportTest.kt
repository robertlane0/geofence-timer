package com.geofenceoffline.map

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny local HTTP server keeps the transport tests network-independent and dependency-free.
 * It handles one request per connection and speaks just enough HTTP/1.1 for HttpURLConnection.
 */
class OverpassTransportTest {
    private data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    private lateinit var server: ServerSocket
    private lateinit var endpoint: String
    private val requests = CopyOnWriteArrayList<String>()
    private val executor = Executors.newCachedThreadPool()

    private fun start(responder: (requestNumber: Int) -> Response) {
        server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        endpoint = "http://127.0.0.1:${server.localPort}/api/interpreter"
        executor.execute {
            var requestNumber = 0
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val request = readRequest(socket.getInputStream())
                        requests.add(request)
                        writeResponse(socket, responder(requestNumber++))
                    }
                } catch (_: Exception) {
                    if (!server.isClosed) {
                        // Ignore malformed test connections; assertions inspect requests/responses.
                    }
                }
            }
        }
    }

    @Before
    fun configure() {
        requests.clear()
    }

    @After
    fun stop() {
        if (::server.isInitialized) server.close()
        executor.shutdownNow()
    }

    private fun readRequest(input: InputStream): String {
        val headers = StringBuilder()
        while (true) {
            val line = readLine(input)
            headers.append(line).append('\n')
            if (line.isEmpty()) break
        }
        val contentLength = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = input.readNBytes(contentLength).toString(Charsets.UTF_8)
        return headers.toString() + "\n" + body
    }

    private fun readLine(input: InputStream): String {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) break
            if (next == '\r'.code) {
                input.read() // '\n'
                break
            }
            builder.append(next.toChar())
        }
        return builder.toString()
    }

    private fun writeResponse(socket: Socket, response: Response) {
        val body = response.body.toByteArray()
        val output = BufferedOutputStream(socket.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 ${response.code} Test\r\n")
            append("Content-Length: ${body.size}\r\n")
            response.headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun tempFile(): File = File.createTempFile("overpass-test-", ".json").apply { deleteOnExit() }

    @Test
    fun successfulPostWritesResponseToDestination() = runBlocking {
        start { Response(200, """{"elements":[]}""") }
        val destination = tempFile()
        HttpOverpassTransport(endpoint, readTimeoutMillis = 5_000).download(
            "[out:json];node(1);out;", destination
        ) { _, _ -> }
        assertEquals("""{"elements":[]}""", destination.readText())
        assertTrue(requests.single().contains("POST /api/interpreter"))
        assertTrue(requests.single().contains("data="))
        assertTrue(requests.single().contains("node%281%29"))
    }

    @Test
    fun rateLimitRetriesAndHonoursRetryAfter() = runBlocking {
        val attempts = AtomicInteger(0)
        start {
            if (attempts.getAndIncrement() == 0) {
                Response(429, "busy", mapOf("Retry-After" to "0"))
            } else {
                Response(200, """{"elements":[]}""")
            }
        }
        val destination = tempFile()
        HttpOverpassTransport(endpoint, readTimeoutMillis = 5_000, maxRetries = 1).download(
            "q", destination
        ) { _, _ -> }
        assertEquals(2, attempts.get())
        assertEquals("""{"elements":[]}""", destination.readText())
    }

    @Test
    fun nonRetryableBadRequestFailsImmediately() = runBlocking {
        val attempts = AtomicInteger(0)
        start {
            attempts.incrementAndGet()
            Response(400, "bad query")
        }
        val destination = tempFile()
        val error = runCatching {
            HttpOverpassTransport(endpoint, readTimeoutMillis = 5_000, maxRetries = 2).download(
                "q", destination
            ) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(error is OverpassException)
        assertEquals(1, attempts.get())
        assertTrue(error?.message?.contains("rejected") == true)
    }

    @Test
    fun emptyResponseIsRejected() = runBlocking {
        start { Response(200, "") }
        val destination = tempFile()
        val error = runCatching {
            HttpOverpassTransport(endpoint, readTimeoutMillis = 5_000, maxRetries = 0).download(
                "q", destination
            ) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(error is OverpassException)
    }
}
