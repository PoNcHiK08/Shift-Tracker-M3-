package com.vkurylo.shifttracker.data.sync

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.domain.repository.BackupCounts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** A safety backup kept on this phone. */
data class SafetyBackupFile(
    /** The file's name: its id for [FileSafetyBackup.read]. */
    val name: String,
    val createdAt: Long,
    /** A [SafetyBackup.Reason]. */
    val reason: String,
    /** What it holds; null if it can't be read as a backup. */
    val counts: BackupCounts?,
)

/**
 * Before this phone's data is replaced (sync taking an account's data instead, a JSON import), a JSON export of
 * everything is written to files/safety-backups/ (the newest [KEEP] are kept; nothing is written when the phone
 * holds no data). It imports like any backup; Settings lists them and saves one to a file the user picks.
 */
@Singleton
class FileSafetyBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: JsonBackupRepository,
    private val clock: WallClock,
) : SafetyBackup {

    private val dir get() = File(context.filesDir, DIR)

    private val saved = MutableStateFlow(0)

    /** Changes after every backup written, so a list of them can refresh. */
    val changes: StateFlow<Int> = saved.asStateFlow()

    override suspend fun save(reason: String) = withContext(Dispatchers.IO) {
        if (!backup.hasData()) return@withContext
        val dir = dir.apply { mkdirs() }
        val stamp = STAMP.format(Instant.ofEpochMilli(clock.nowMillis()))
        // Written whole, then renamed: a write cut short (a full disk) leaves no half backup in the list.
        val partial = File(dir, "$stamp-$reason.partial")
        try {
            partial.writeText(backup.exportJson())
            if (!partial.renameTo(File(dir, "$stamp-$reason.json"))) throw java.io.IOException("Couldn't finish $partial")
        } finally {
            partial.delete()
        }
        files().drop(KEEP).forEach { it.delete() }
        saved.update { it + 1 }
    }

    /** The backups kept, newest first. */
    suspend fun list(): List<SafetyBackupFile> = withContext(Dispatchers.IO) {
        files().map { file ->
            SafetyBackupFile(
                name = file.name,
                createdAt = createdAt(file),
                reason = file.name.removeSuffix(".json").drop(STAMP_LENGTH + 1),
                counts = runCatching { backup.countsOf(file.readText()) }.getOrNull(),
            )
        }
    }

    /** The backup named [name] (one [list] returned), as JSON. */
    suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        val file = files().firstOrNull { it.name == name } ?: throw FileNotFoundException("No safety backup $name")
        file.readText()
    }

    /**
     * A copy of the backup [name] that another app may read, named so that it says what it is, as a content Uri
     * (FileProvider; only this copy is shared, never the backups folder). Each call replaces the previous copy.
     */
    suspend fun shareable(name: String): Uri = withContext(Dispatchers.IO) {
        val json = read(name)
        val dir = File(context.cacheDir, SHARED_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val copy = File(dir, "shift-tracker-safety-$name").apply { writeText(json) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    }

    private fun files(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().sortedByDescending { it.name }

    /** From the name (written in UTC); the file's time if the name isn't one this class wrote. */
    private fun createdAt(file: File): Long = runCatching {
        LocalDateTime.parse(file.name.take(STAMP_LENGTH), STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrElse { file.lastModified() }

    companion object {
        const val DIR = "safety-backups"

        /** In the cache: the copy being shared (xml/shared_files). */
        const val SHARED_DIR = "shared"
        const val KEEP = 5
        private const val STAMP_LENGTH = 15 // yyyyMMdd-HHmmss
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
