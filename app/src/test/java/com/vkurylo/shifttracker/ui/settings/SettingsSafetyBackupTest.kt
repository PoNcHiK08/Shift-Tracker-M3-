package com.vkurylo.shifttracker.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.data.sync.FakeCloudAuth
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.FileSafetyBackup
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.data.sync.forgetFileProviderFolders
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Settings → Data → Safety backups: listed as the app saves them, and each one saved to a file as it is. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class SettingsSafetyBackupTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private val backups = FileSafetyBackup(context, JsonBackupRepository(db), WallClock.System)
    private val viewModel = settingsViewModel(context, db, FakeCloudAuth(), FakeRemoteStore(), safetyBackups = backups)
    private val snackbars = SnackbarHostState()
    private val shownMessages = mutableListOf<String>()

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, FileSafetyBackup.DIR).deleteRecursively()
        File(context.cacheDir, FileSafetyBackup.SHARED_DIR).deleteRecursively()
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun showSettings() = composeRule.setContent {
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            snapshotFlow { snackbars.currentSnackbarData?.visuals?.message }.filterNotNull().collect { shownMessages += it }
        }
        ShiftTrackerTheme(dynamicColor = false) {
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbars, scope)) {
                SettingsScreen(onManageJobs = {}, onImportIcs = {}, viewModel = viewModel)
            }
        }
    }

    private fun scrollTo(text: String) = composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    @Test
    fun withoutBackupsTheRowSaysWhenOneIsMadeAndCantBeOpened() {
        showSettings()
        scrollTo(str(R.string.safety_backups_none))
        composeRule.onNodeWithText(str(R.string.safety_backups_none)).assertExists()
        composeRule.onNodeWithText(str(R.string.safety_backups)).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(str(R.string.safety_backups_message, FileSafetyBackup.KEEP)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aNewBackupShowsUpAndIsSavedToTheChosenFileAsItIs() {
        runBlocking { db.jobDao().upsert(Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1).toEntity()) }
        showSettings()
        scrollTo(str(R.string.safety_backups))
        waitForText(str(R.string.safety_backups_none))

        // The app saves one (here directly; an import or "use the account's data" does it): the row updates.
        runBlocking { backups.save(SafetyBackup.Reason.BEFORE_IMPORT) }
        waitFor { composeRule.onAllNodesWithText(str(R.string.safety_backups_summary, 1)).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText(str(R.string.safety_backups)).performClick()
        waitForText(str(R.string.safety_backups_message, FileSafetyBackup.KEEP))
        composeRule.onNodeWithText(str(R.string.safety_backup_reason_import) + "\n" + str(R.string.safety_backup_counts, 1, 0, 0, 0)).assertExists()

        // What the system picker returns, saved as it is.
        val name = runBlocking { backups.list() }.single().name
        val target = File(context.cacheDir, "picked.json")
        viewModel.exportSafetyBackup(name, Uri.fromFile(target))
        waitFor { str(R.string.safety_backup_saved) in shownMessages }
        assertEquals(runBlocking { backups.read(name) }, target.readText())
    }

    @Test
    fun shareOpensTheShareSheetWithAReadableCopyNamedForWhatItIs() {
        forgetFileProviderFolders()
        runBlocking {
            db.jobDao().upsert(Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1).toEntity())
            backups.save(SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA)
        }
        val name = runBlocking { backups.list() }.single().name
        showSettings()
        scrollTo(str(R.string.safety_backups))
        waitFor { composeRule.onAllNodesWithText(str(R.string.safety_backups_summary, 1)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(str(R.string.safety_backups)).performClick()
        composeRule.onNodeWithContentDescription(str(R.string.safety_backup_share)).performClick()

        var chooser: Intent? = null
        waitFor { (chooser ?: shadowOf(context).nextStartedActivity)?.also { chooser = it } != null }
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertTrue("the app picked may read it", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals("shift-tracker-safety-$name", uri.lastPathSegment)
        val shared = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertEquals(runBlocking { backups.read(name) }, shared)
    }
}
