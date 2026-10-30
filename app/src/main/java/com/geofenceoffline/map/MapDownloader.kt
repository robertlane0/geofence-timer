package com.geofenceoffline.map

import android.content.Context
import android.os.StatFs
import com.geofenceoffline.data.MapArea
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.geo.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class DownloadStage { PREPARING, FETCHING, PARSING, SAVING }

data class DownloadProgress(
    val stage: DownloadStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String = ""
)

class MapDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Shared limits keep the picker, the downloader, and the Overpass response cap consistent. */
object MapLimits {
    const val MAX_AREA_KM2 = 25.0
    const val MAX_DIMENSION_METERS = 8_000.0

    fun exceeds(bounds: BoundingBox): Boolean =
        bounds.areaKm2 > MAX_AREA_KM2 ||
            bounds.widthMeters > MAX_DIMENSION_METERS ||
            bounds.heightMeters > MAX_DIMENSION_METERS
}

class MapDownloader(
    context: Context,
    private val transport: OverpassTransport = HttpOverpassTransport()
) {
    private val appContext = context.applicationContext
    private val mapRepository = MapRepository(appContext)

    suspend fun download(
        name: String,
        bounds: BoundingBox,
        onProgress: (DownloadProgress) -> Unit
    ): MapArea = withContext(Dispatchers.IO) {
        if (name.isBlank()) throw MapDownloadException("Enter a name for this map.")
        if (MapLimits.exceeds(bounds)) {
            throw MapDownloadException(
                "This area is too large for one download. Keep it inside " +
                    "${MapLimits.MAX_AREA_KM2.toInt()} km² and " +
                    "${MapLimits.MAX_DIMENSION_METERS.toInt()} m per side."
            )
        }
        val free = StatFs(appContext.filesDir.path).availableBytes
        val reserve = MIN_FREE_BYTES + MapSizeEstimator.estimateBytes(bounds)
        if (free < reserve) {
            throw MapDownloadException("Not enough free storage to save this map.")
        }
        mapRepository.cleanupTemporaryDownloads()
        val id = UUID.randomUUID().toString()
        val temp = mapRepository.newDownloadFile()
        try {
            onProgress(DownloadProgress(DownloadStage.PREPARING, message = "Building Overpass query"))
            val query = OverpassQuery.build(bounds)
            onProgress(DownloadProgress(DownloadStage.FETCHING, message = "Fetching data from OSM"))
            transport.download(query, temp) { downloaded, total ->
                onProgress(
                    DownloadProgress(
                        DownloadStage.FETCHING,
                        downloaded,
                        total,
                        "Fetching data from OSM"
                    )
                )
            }
            val bytes = temp.length()
            if (bytes <= 0) throw MapDownloadException("The map server returned an empty response.")
            onProgress(DownloadProgress(DownloadStage.PARSING, bytes, bytes, "Parsing and storing"))
            val text = temp.readText(Charsets.UTF_8)
            val data = try {
                OsmParser.parse(text, bytes)
            } catch (e: MapParseException) {
                throw MapDownloadException(e.message ?: "The map data could not be read.", e)
            }
            onProgress(DownloadProgress(DownloadStage.SAVING, bytes, bytes, "Preparing offline map"))
            val area = mapRepository.install(
                id = id,
                name = name.trim(),
                bounds = bounds,
                sourceFile = temp,
                sizeBytes = bytes,
                featureCount = data.featureCount,
                nodeCount = data.nodeCount
            ) ?: throw MapDownloadException("Could not save the offline map.")
            area
        } catch (e: kotlinx.coroutines.CancellationException) {
            temp.delete()
            throw e
        } catch (e: MapDownloadException) {
            temp.delete()
            throw e
        } catch (e: OverpassException) {
            temp.delete()
            throw MapDownloadException(e.message ?: "The map download failed.", e)
        } catch (e: Exception) {
            temp.delete()
            throw MapDownloadException("The map download failed. Please try again.", e)
        }
    }

    companion object {
        private const val MIN_FREE_BYTES = 5_000_000L
    }
}

object MapSizeEstimator {
    /**
     * A deliberately conservative estimate. If a live preview is available, scale its byte size by
     * the ratio between selection and preview areas; otherwise use a dense-urban heuristic.
     */
    fun estimateBytes(
        selection: BoundingBox,
        previewBytes: Long? = null,
        previewBounds: BoundingBox? = null
    ): Long {
        val area = selection.areaKm2.coerceAtLeast(0.000001)
        if (previewBytes != null && previewBounds != null && previewBounds.areaKm2 > 0.0) {
            val scaled = previewBytes * (area / previewBounds.areaKm2)
            return scaled.toLong().coerceIn(20_000L, 29_500_000L)
        }
        return (area * 3_000_000.0).toLong().coerceIn(20_000L, 29_500_000L)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    fun preloadFile(file: File): OsmMapData? = runCatching {
        OsmParser.parse(file.readText(Charsets.UTF_8), file.length())
    }.getOrNull()
}
