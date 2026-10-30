package com.geofenceoffline.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.map.MapDownloadException
import com.geofenceoffline.map.MapDownloader
import com.geofenceoffline.map.MapSizeEstimator
import com.geofenceoffline.map.OverpassException
import com.geofenceoffline.map.OverpassTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineMapStorageTest {
    private lateinit var context: android.content.Context
    private lateinit var maps: MapRepository
    private lateinit var fixture: ByteArray
    private val bounds = BoundingBox(37.7740, -122.4200, 37.7760, -122.4170)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        maps = MapRepository(context)
        maps.deleteAll()
        fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sf-fixture.json").bufferedReader().use { it.readText() }
            .toByteArray()
    }

    @After
    fun tearDown() {
        maps.deleteAll()
    }

    private fun fakeTransport(failAfterWrite: Boolean = false) = object : OverpassTransport {
        override suspend fun download(
            query: String,
            destination: File,
            onProgress: (Long, Long?) -> Unit
        ) {
            destination.writeBytes(fixture)
            onProgress(fixture.size.toLong(), fixture.size.toLong())
            if (failAfterWrite) throw OverpassException("simulated network failure")
        }
    }

    @Test
    fun downloadInstallsParseableOfflineFileAndDeletesWithRecord() = runBlocking {
        val area = MapDownloader(context, fakeTransport()).download("Fixture", bounds) { }
        assertEquals("Fixture", area.name)
        assertEquals(bounds, area.bounds)
        assertTrue(area.featureCount > 20)
        assertTrue(area.sizeBytes > 0)
        val file = maps.fileFor(area)
        assertTrue(file.exists())
        val data = MapSizeEstimator.preloadFile(file)
        assertTrue(data != null && data.featureCount == area.featureCount)
        assertTrue(maps.listAll().any { it.id == area.id })

        maps.delete(area.id)
        assertFalse(file.exists())
        assertTrue(maps.listAll().none { it.id == area.id })
    }

    @Test
    fun failedDownloadLeavesNoRecordAndNoTemporaryFile() = runBlocking {
        val error = runCatching {
            MapDownloader(context, fakeTransport(failAfterWrite = true))
                .download("Broken", bounds) { }
        }.exceptionOrNull()
        assertTrue(error is MapDownloadException)
        assertTrue(maps.listAll().isEmpty())
        val leftovers = maps.mapsDirectory.listFiles().orEmpty().filter {
            it.name.startsWith(".download-")
        }
        assertTrue("temporary download was not deleted: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun oversizedAreaIsRejectedBeforeNetwork() = runBlocking {
        var contacted = false
        val transport = object : OverpassTransport {
            override suspend fun download(
                query: String,
                destination: File,
                onProgress: (Long, Long?) -> Unit
            ) {
                contacted = true
            }
        }
        val huge = BoundingBox(37.0, -123.0, 38.0, -122.0)
        val error = runCatching {
            MapDownloader(context, transport).download("Huge", huge) { }
        }.exceptionOrNull()
        assertTrue(error is MapDownloadException)
        assertFalse(contacted)
        assertTrue(maps.listAll().isEmpty())
    }

    @Test
    fun interruptedTemporaryDownloadIsCleanedOnNextStartup() {
        val directory = maps.mapsDirectory
        val orphan = File(directory, ".download-orphan.tmp").apply { writeText("partial") }
        assertTrue(orphan.exists())
        maps.cleanupTemporaryDownloads()
        assertFalse(orphan.exists())
    }
}
