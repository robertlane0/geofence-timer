package com.geofenceoffline.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.geofenceoffline.geo.BoundingBox
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns downloaded map datasets. A download is written to a temporary file, validated, and only then
 * moved into place and recorded in SQLite. Deleting a map removes both its record and file.
 */
class MapRepository(context: Context) {
    private val appContext = context.applicationContext
    private val helper = AppDatabase.get(context)

    val mapsDirectory: File
        get() = File(appContext.filesDir, "offline_maps").apply { mkdirs() }

    fun listAll(): List<MapArea> {
        val result = ArrayList<MapArea>()
        helper.readableDatabase.query(
            "map_areas", null, null, null, null, null, "downloaded_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toMapArea())
        }
        return result
    }

    fun get(id: String): MapArea? =
        helper.readableDatabase.query(
            "map_areas", null, "id = ?", arrayOf(id), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toMapArea() else null }

    fun fileFor(area: MapArea): File = File(mapsDirectory, area.fileName)

    fun newDownloadFile(): File {
        mapsDirectory.mkdirs()
        return File(mapsDirectory, ".download-${UUID.randomUUID()}.tmp")
    }

    /** Atomically installs a completed download and records it. Returns null if the insert failed. */
    fun install(
        id: String,
        name: String,
        bounds: BoundingBox,
        sourceFile: File,
        sizeBytes: Long,
        featureCount: Int,
        nodeCount: Int
    ): MapArea? {
        val fileName = "$id.json"
        val target = File(mapsDirectory, fileName)
        if (!sourceFile.renameTo(target)) {
            sourceFile.copyTo(target, overwrite = true)
            sourceFile.delete()
        }
        val area = MapArea(
            id = id,
            name = name,
            bounds = bounds,
            sizeBytes = sizeBytes,
            featureCount = featureCount,
            nodeCount = nodeCount,
            downloadedAt = System.currentTimeMillis(),
            fileName = fileName
        )
        val values = AppDatabase.contentValuesOf(
            "id" to area.id,
            "name" to area.name,
            "south" to area.bounds.south,
            "west" to area.bounds.west,
            "north" to area.bounds.north,
            "east" to area.bounds.east,
            "size_bytes" to area.sizeBytes,
            "feature_count" to area.featureCount,
            "node_count" to area.nodeCount,
            "downloaded_at" to area.downloadedAt,
            "file_name" to area.fileName
        )
        val rowId = helper.writableDatabase.insertWithOnConflict(
            "map_areas", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
        )
        if (rowId == -1L) {
            target.delete()
            return null
        }
        return area
    }

    fun rename(id: String, name: String) {
        helper.writableDatabase.update(
            "map_areas", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id)
        )
    }

    fun delete(id: String) {
        val area = get(id) ?: return
        helper.writableDatabase.delete("map_areas", "id = ?", arrayOf(id))
        fileFor(area).delete()
    }

    fun cleanupTemporaryDownloads() {
        mapsDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(".download-") && file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    fun deleteAll() {
        listAll().forEach { delete(it.id) }
        mapsDirectory.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = listAll().sumOf { it.sizeBytes }

    private fun Cursor.toMapArea(): MapArea = MapArea(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        bounds = BoundingBox(
            south = getDouble(getColumnIndexOrThrow("south")),
            west = getDouble(getColumnIndexOrThrow("west")),
            north = getDouble(getColumnIndexOrThrow("north")),
            east = getDouble(getColumnIndexOrThrow("east"))
        ),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        featureCount = getInt(getColumnIndexOrThrow("feature_count")),
        nodeCount = getInt(getColumnIndexOrThrow("node_count")),
        downloadedAt = getLong(getColumnIndexOrThrow("downloaded_at")),
        fileName = getString(getColumnIndexOrThrow("file_name"))
    )
}

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        accuracyThresholdMeters = prefs.getInt(KEY_ACCURACY, DEFAULT_ACCURACY).coerceIn(5, 500),
        locationTimeoutSeconds = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT).coerceIn(15, 300),
        lateGraceMinutes = prefs.getInt(KEY_LATE_GRACE, DEFAULT_LATE_GRACE).coerceIn(1, 120),
        notifyResults = prefs.getBoolean(KEY_NOTIFY, true)
    )

    fun setAccuracyThreshold(meters: Int) {
        prefs.edit().putInt(KEY_ACCURACY, meters.coerceIn(5, 500)).apply()
    }

    fun setLocationTimeout(seconds: Int) {
        prefs.edit().putInt(KEY_TIMEOUT, seconds.coerceIn(15, 300)).apply()
    }

    fun setLateGraceMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_LATE_GRACE, minutes.coerceIn(1, 120)).apply()
    }

    fun setNotifyResults(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY, enabled).apply()
    }

    companion object {
        const val DEFAULT_ACCURACY = 50
        const val DEFAULT_TIMEOUT = 90
        const val DEFAULT_LATE_GRACE = 15
        private const val KEY_ACCURACY = "accuracy_threshold_m"
        private const val KEY_TIMEOUT = "location_timeout_s"
        private const val KEY_LATE_GRACE = "late_grace_min"
        private const val KEY_NOTIFY = "notify_results"
    }
}
