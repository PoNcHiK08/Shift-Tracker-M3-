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
import com.vkurylo.shifttracker.domain.repository.BackupCounts
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

    @Test
    fun theListIsNewestFirstWithWhyAndWhatAndEachReadsBackAsWritten() = runBlocking {
        val job = db.jobDao().upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1).toEntity())
        backups.save(SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA)
        now += 60_000
        db.shiftDao().upsert(
            com.vkurylo.shifttracker.domain.model.Shift(
                jobId = job, date = java.time.LocalDate.of(2026, 11, 2), start = java.time.LocalTime.of(8, 0),
                end = java.time.LocalTime.of(16, 0), hourlyRateGrosze = 3300,
            ).toEntity(),
        )
        val before = backups.changes.value
        backups.save(SafetyBackup.Reason.BEFORE_IMPORT)
        assertTrue("a new backup is announced", backups.changes.value != before)

        val list = backups.list()
        assertEquals(listOf(SafetyBackup.Reason.BEFORE_IMPORT, SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA), list.map { it.reason })
        assertEquals(listOf(now, now - 60_000).map { it / 1_000 * 1_000 }, list.map { it.createdAt })
        assertEquals(listOf(BackupCounts(1, 1, 0), BackupCounts(1, 0, 0)), list.map { it.counts })
        assertEquals(File(dir, list[1].name).readText(), backups.read(list[1].name))
    }

    @Test
    fun sharingHandsOutOnlyAFreshCopyNeverTheBackupsFolder() = runBlocking {
        forgetFileProviderFolders()
        db.jobDao().upsert(Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1).toEntity())
        backups.save(SafetyBackup.Reason.BEFORE_IMPORT)
        now += 1_000
        backups.save(SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA)
        val (newest, older) = backups.list().map { it.name }

        backups.shareable(older)
        val uri = backups.shareable(newest)
        val shared = File(context.cacheDir, FileSafetyBackup.SHARED_DIR)
        assertEquals("only the latest copy is kept", listOf("shift-tracker-safety-$newest"), shared.list()!!.toList())
        assertEquals(backups.read(newest), File(shared, "shift-tracker-safety-$newest").readText())
        assertEquals("shift-tracker-safety-$newest", uri.lastPathSegment)
        // The provider can't point at the backups themselves.
        val direct = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, newest))
        }
        assertTrue(direct.toString(), direct.exceptionOrNull() is IllegalArgumentException)
        shared.deleteRecursively()
        Unit
    }

    @Test
    fun onlyABackupInTheListCanBeRead() = runBlocking {
        File(context.filesDir, "secret.json").writeText("{}")
        dir.mkdirs()
        File(dir, "notes.txt").writeText("x")
        for (name in listOf("../secret.json", "notes.txt", "missing.json")) {
            assertTrue(name, runCatching { backups.read(name) }.exceptionOrNull() is java.io.FileNotFoundException)
        }
        // A file that isn't a backup is listed as unreadable rather than breaking the list.
        File(dir, "20261110-120000-before-import.json").writeText("not json")
        assertEquals(listOf<BackupCounts?>(null), backups.list().map { it.counts })
        File(context.filesDir, "secret.json").delete()
        Unit
    }
}
