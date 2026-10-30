package com.geofenceoffline.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Small SQLite schema kept deliberately dependency-free. Check results have no columns capable of
 * holding a device location: only times, names/ids, status and a failure reason are stored.
 */
class AppDatabase(context: Context, databaseName: String = "geofence_offline.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE map_areas (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                south REAL NOT NULL,
                west REAL NOT NULL,
                north REAL NOT NULL,
                east REAL NOT NULL,
                size_bytes INTEGER NOT NULL,
                feature_count INTEGER NOT NULL,
                node_count INTEGER NOT NULL,
                downloaded_at INTEGER NOT NULL,
                file_name TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE geofences (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                center_lat REAL,
                center_lon REAL,
                radius_m REAL,
                polygon TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE schedules (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                times TEXT NOT NULL DEFAULT '',
                weekdays TEXT NOT NULL DEFAULT '',
                window_start INTEGER NOT NULL DEFAULT 0,
                window_end INTEGER NOT NULL DEFAULT 0,
                interval_minutes INTEGER NOT NULL DEFAULT 10,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE schedule_geofences (
                schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
                geofence_id TEXT NOT NULL REFERENCES geofences(id) ON DELETE CASCADE,
                PRIMARY KEY (schedule_id, geofence_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE check_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schedule_id TEXT,
                schedule_name TEXT NOT NULL,
                geofence_id TEXT,
                geofence_name TEXT NOT NULL,
                scheduled_at INTEGER NOT NULL,
                executed_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                reason TEXT NOT NULL,
                occurrence_key TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_check_results_scheduled ON check_results(scheduled_at DESC)")
        db.execSQL("CREATE INDEX idx_check_results_status ON check_results(status)")
        db.execSQL("CREATE INDEX idx_check_results_geofence ON check_results(geofence_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first public schema. Future migrations belong here.
    }

    companion object {
        const val VERSION = 1
        private const val DATABASE_NAME = "geofence_offline.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context.applicationContext, DATABASE_NAME).also { instance = it }
            }

        fun contentValuesOf(vararg pairs: Pair<String, Any?>): ContentValues =
            ContentValues().apply {
                pairs.forEach { (key, value) ->
                    when (value) {
                        null -> putNull(key)
                        is String -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        is Double -> put(key, value)
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            }
    }
}
