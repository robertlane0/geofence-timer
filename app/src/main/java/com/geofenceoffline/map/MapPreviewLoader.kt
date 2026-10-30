package com.geofenceoffline.map

import android.content.Context
import com.geofenceoffline.geo.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches a lightweight Overpass extract only to provide visual context while choosing a download
 * area. It is never installed as an offline map and is always deleted after parsing.
 */
class MapPreviewLoader(
    context: Context,
    private val transport: OverpassTransport = HttpOverpassTransport(
        readTimeoutMillis = 45_000,
        maxRetries = 1
    )
) {
    private val cacheDirectory = context.applicationContext.cacheDir

    suspend fun load(
        bounds: BoundingBox,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): OsmMapData = withContext(Dispatchers.IO) {
        cacheDirectory.mkdirs()
        val file = File.createTempFile("overpass-preview-", ".json", cacheDirectory)
        try {
            transport.download(OverpassQuery.buildPreview(bounds), file, onProgress)
            OsmParser.parse(file.readText(Charsets.UTF_8), file.length())
        } finally {
            file.delete()
        }
    }
}
