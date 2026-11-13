package com.vkurylo.shifttracker.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SyncPreferencesTest {

    @Test
    fun theLinkTheSettingsAndTheLastOutcomeAreKept() = runBlocking {
        val prefs = testSyncPreferences()
        val state = SyncState(boundUid = "uid", boundEmail = "a@example.com", epoch = "e1", cursor = 42, lastSyncedAt = 7, format = SYNC_FORMAT)
        prefs.update { state }
        val outcome = SyncOutcome(at = 9, downloaded = 1, uploaded = 2, skipped = 3, limited = true, problem = SyncProblem.CLOCK, detail = "30")
        prefs.updateSettings { SyncSettings(enabled = true, cloudReset = true, writesDay = 20_400, writesToday = 12, last = outcome) }

        assertEquals(state, prefs.get())
        assertEquals(SyncSettings(true, true, 20_400, 12, outcome), prefs.currentSettings())

        // Unlinking keeps the settings; clearing the outcome removes it.
        prefs.update { SyncState(lastSyncedAt = it.lastSyncedAt) }
        prefs.updateSettings { it.copy(last = null) }
        assertEquals(SyncState(lastSyncedAt = 7), prefs.get())
        assertEquals(SyncSettings(true, true, 20_400, 12, null), prefs.currentSettings())
    }

    @Test
    fun theSyncFileIsLeftOutOfEveryBackup() {
        // Unit tests run in the app module.
        val excluded = "datastore/${SyncPreferences.FILE}.preferences_pb"
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val xml = File(rules).readText()
            val includes = Regex("""<include domain="file" path="datastore" />""").findAll(xml).count()
            val excludes = Regex("""<exclude domain="file" path="${Regex.escape(excluded)}" />""").findAll(xml).count()
            assertTrue(rules, includes > 0)
            assertEquals(rules, includes, excludes)
        }
    }
}
