package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.model.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Safety backups: written only when there is data to lose, the newest few kept, each one a backup that imports. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileSafetyBackupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private var now = 1_762_000_000_000L
    private val backups = FileSafetyBackup(context, JsonBackupRepository(db), WallClock { now })
    private val dir = File(context.filesDir, FileSafetyBackup.DIR)

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun files() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun aPhoneWithoutDataWritesNothing() = runBlocking {
        backups.save(SafetyBackup.Reason.BEFORE_IMPORT)
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun theDataIsSavedAsABackupThatImportsAndTheNewestFiveAreKept() = runBlocking {
        db.jobDao().upsert(Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1).toEntity())
        val start = now
        repeat(7) {
            now += 1_000
            backups.save(if (it % 2 == 0) SafetyBackup.Reason.BEFORE_IMPORT else SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA)
        }

        // Named by the time (UTC) and the reason; the two oldest are gone.
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)
        val expected = (2 until 7).map { i ->
            stamp.format(java.time.Instant.ofEpochMilli(start + (i + 1) * 1_000L)) +
                if (i % 2 == 0) "-before-import.json" else "-before-taking-cloud-data.json"
        }
        val names = files()
        assertEquals(expected, names)
        assertEquals(FileSafetyBackup.KEEP, names.size)
        val restored = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
        try {
            JsonBackupRepository(restored).importJson(File(dir, names.last()).readText())
            assertEquals(listOf("Kawiarnia"), restored.jobDao().getAll().map { it.name })
        } finally {
            restored.close()
        }
    }
}
