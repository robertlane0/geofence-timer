package com.geofenceoffline.map

import com.geofenceoffline.geo.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface OverpassTransport {
    suspend fun download(
        query: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    )
}

/**
 * Minimal Overpass client built on HttpURLConnection. It retries transient failures, honours
 * Retry-After for rate limits, caps response size, and never logs request URLs or response contents.
 */
class HttpOverpassTransport(
    private val endpoint: String = ENDPOINT,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 120_000,
    private val maxRetries: Int = 2
) : OverpassTransport {
    override suspend fun download(
        query: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    doOutput = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                val body = "data=" + java.net.URLEncoder.encode(query, "UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = httpError(code)
                    consumeQuietly(connection.errorStream)
                    if (error.retryable && attempt < maxRetries) {
                        lastError = error
                        delay(retryDelayMillis(connection.getHeaderField("Retry-After"), attempt))
                        continue
                    }
                    throw error
                }

                val total = connection.contentLengthLong.takeIf { it > 0 }
                if (total != null && total > MAX_BYTES) {
                    throw OverpassException(
                        "This area is too large for the map server. Select a smaller area."
                    )
                }
                FileOutputStream(destination, false).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > MAX_BYTES) {
                                throw OverpassException(
                                    "The downloaded map is too large. Select a smaller area."
                                )
                            }
                            output.write(buffer, 0, read)
                            if (downloaded - lastReport >= 128 * 1024 || downloaded == total) {
                                onProgress(downloaded, total)
                                lastReport = downloaded
                            }
                        }
                        if (downloaded == 0L) throw OverpassException("The map server returned an empty response.")
                        onProgress(downloaded, total ?: downloaded)
                    }
                }
                return@withContext
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OverpassException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxRetries) {
                    delay(retryDelayMillis(null, attempt))
                }
            } finally {
                connection?.disconnect()
            }
        }
        throw OverpassException(
            "Could not reach the map server. Check your internet connection and try again.",
            retryable = true,
            cause = lastError
        )
    }

    private fun httpError(code: Int): OverpassException = when (code) {
        429 -> OverpassException(
            "The map server is rate limiting requests. Wait a minute, then try again.",
            retryable = true
        )
        400 -> OverpassException("The map server rejected this area. Try moving or resizing the selection.")
        403 -> OverpassException("The map server refused the request. Try again later.")
        504, 502, 503 -> OverpassException(
            "The map server is busy. Please try again in a moment.",
            retryable = true
        )
        else -> OverpassException("The map download failed (HTTP $code). Please try again.")
    }

    private fun retryDelayMillis(retryAfter: String?, attempt: Int): Long {
        val requested = retryAfter?.trim()?.toLongOrNull()?.times(1000L)
        return (requested ?: (2_000L * (attempt + 1))).coerceIn(500L, 30_000L)
    }

    private fun consumeQuietly(stream: java.io.InputStream?) {
        try {
            stream?.use { input ->
                val buffer = ByteArray(1024)
                var total = 0
                while (total < 4096) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
        } catch (_: IOException) {
            // The response body is intentionally neither logged nor surfaced.
        }
    }

    companion object {
        const val ENDPOINT = "https://overpass.private.coffee/api/interpreter"
        const val MAX_BYTES = 30_000_000L
        private const val USER_AGENT =
            "GeoFenceOffline/1.0 (Android offline geofencing app; contact: local developer)"
    }
}
