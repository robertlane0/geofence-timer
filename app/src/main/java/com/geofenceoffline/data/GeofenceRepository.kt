package com.geofenceoffline.data

import android.content.Context
import android.database.Cursor
import com.geofenceoffline.geo.GeoPoint

class GeofenceRepository(context: Context) {
    private val helper = AppDatabase.get(context)

    fun listAll(): List<Geofence> {
        val result = ArrayList<Geofence>()
        helper.readableDatabase.query(
            "geofences", null, null, null, null, null, "name COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toGeofence())
        }
        return result
    }

    fun listEnabled(): List<Geofence> = listAll().filter { it.enabled }

    fun get(id: String): Geofence? =
        helper.readableDatabase.query(
            "geofences", null, "id = ?", arrayOf(id), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toGeofence() else null }

    fun findContaining(point: GeoPoint): List<Geofence> = listAll().filter { it.contains(point) }

    fun save(geofence: Geofence) {
        val values = AppDatabase.contentValuesOf(
            "id" to geofence.id,
            "name" to geofence.name,
            "type" to geofence.type.name,
            "center_lat" to geofence.center?.lat,
            "center_lon" to geofence.center?.lon,
            "radius_m" to geofence.radiusMeters,
            "polygon" to PersistenceCodec.encodePolygon(geofence.polygon),
            "enabled" to geofence.enabled,
            "created_at" to geofence.createdAt,
            "updated_at" to geofence.updatedAt
        )
        val db = helper.writableDatabase
        val updated = db.update("geofences", values, "id = ?", arrayOf(geofence.id))
        if (updated == 0) {
            db.insertOrThrow("geofences", null, values)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        helper.writableDatabase.update(
            "geofences",
            AppDatabase.contentValuesOf("enabled" to enabled, "updated_at" to System.currentTimeMillis()),
            "id = ?",
            arrayOf(id)
        )
    }

    fun delete(id: String) {
        helper.writableDatabase.delete("geofences", "id = ?", arrayOf(id))
    }

    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM geofences", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}

private fun Cursor.toGeofence(): Geofence {
    val type = runCatching {
        GeofenceType.valueOf(getString(getColumnIndexOrThrow("type")))
    }.getOrDefault(GeofenceType.POLYGON)
    val centerLat = getDoubleOrNull("center_lat")
    val centerLon = getDoubleOrNull("center_lon")
    return Geofence(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        type = type,
        center = if (centerLat != null && centerLon != null) GeoPoint(centerLat, centerLon) else null,
        radiusMeters = getDoubleOrNull("radius_m"),
        polygon = PersistenceCodec.decodePolygon(getString(getColumnIndexOrThrow("polygon"))),
        enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )
}

internal fun Cursor.getDoubleOrNull(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}
