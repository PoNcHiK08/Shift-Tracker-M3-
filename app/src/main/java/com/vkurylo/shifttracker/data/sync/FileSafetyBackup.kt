package com.vkurylo.shifttracker.data.sync

import android.content.Context
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.WallClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Before this phone's data is replaced (sync taking an account's data instead, a JSON import), a JSON export of
 * everything is written to files/safety-backups/ (the newest [KEEP] are kept; nothing is written when the phone
 * holds no data). It imports like any backup.
 */
@Singleton
class FileSafetyBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: JsonBackupRepository,
    private val clock: WallClock,
) : SafetyBackup {

    override suspend fun save(reason: String) = withContext(Dispatchers.IO) {
        if (!backup.hasData()) return@withContext
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = STAMP.format(Instant.ofEpochMilli(clock.nowMillis()))
        File(dir, "$stamp-$reason.json").writeText(backup.exportJson())
        dir.listFiles { file -> file.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        Unit
    }

    companion object {
        const val DIR = "safety-backups"
        const val KEEP = 5
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
