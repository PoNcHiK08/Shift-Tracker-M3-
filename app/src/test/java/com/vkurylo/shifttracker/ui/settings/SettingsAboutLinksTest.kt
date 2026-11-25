package com.vkurylo.shifttracker.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.BuildConfig
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.sync.FakeCloudAuth
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings → About: the version, and (with a site) links to the privacy policy and the account deletion page. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class SettingsAboutLinksTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun showSettings(site: String) {
        val viewModel = settingsViewModel(context, db, FakeCloudAuth(), FakeRemoteStore())
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            ShiftTrackerTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                    SettingsScreen(onManageJobs = {}, onImportIcs = {}, viewModel = viewModel, siteUrl = site)
                }
            }
        }
    }

    private fun scrollTo(text: String) = composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    private fun opened(): String? = shadowOf(context).nextStartedActivity?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString

    @Test
    fun withASiteAboutLinksThePrivacyPolicyAndTheDeletionPage() {
        showSettings("https://demo-project.web.app")
        val version = str(R.string.version, BuildConfig.VERSION_NAME)
        scrollTo(version)

        scrollTo(str(R.string.privacy_policy))
        composeRule.onNodeWithText(str(R.string.privacy_policy)).performClick()
        assertEquals("https://demo-project.web.app/privacy?lang=en", opened())

        scrollTo(str(R.string.delete_account_page))
        composeRule.onNodeWithText(str(R.string.delete_account_page)).performClick()
        assertEquals("https://demo-project.web.app/delete-account?lang=en", opened())
    }

    @Test
    fun withoutASiteAboutShowsOnlyTheVersion() {
        showSettings("")
        scrollTo(str(R.string.version, BuildConfig.VERSION_NAME))
        assertTrue(composeRule.onAllNodesWithText(str(R.string.privacy_policy)).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText(str(R.string.delete_account_page)).fetchSemanticsNodes().isEmpty())
    }
}
