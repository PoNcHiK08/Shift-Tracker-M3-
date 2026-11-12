package com.vkurylo.shifttracker.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.FakeCloudAuth
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.firebase.SignInException
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.flow.filterNotNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings → Account & sync as the user sees it, with the account service replaced: a successful sign-in shows
 * the account (and that the cloud lets it in), closing Google's sheet changes nothing, and every other failure
 * shows why, with its code, in the row and in a snackbar.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class SettingsSignInTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private val auth = FakeCloudAuth()
    private val snackbars = SnackbarHostState()
    private val shownMessages = mutableListOf<String>()

    @After
    fun tearDown() = db.close()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Waits for [condition], keeping the main thread going (the ViewModel's work resumes there). */
    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun showSettings() {
        val viewModel = settingsViewModel(context, db, auth, FakeRemoteStore())
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            // Every snackbar message, recorded. No SnackbarHost draws them, so any text found on screen is the
            // settings row's own.
            LaunchedEffect(Unit) {
                snapshotFlow { snackbars.currentSnackbarData?.visuals?.message }.filterNotNull().collect { shownMessages += it }
            }
            ShiftTrackerTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbars, scope)) {
                    SettingsScreen(onManageJobs = {}, onImportIcs = {}, viewModel = viewModel)
                }
            }
        }
    }

    private fun scrollTo(text: String) = composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    private fun tapSignIn() {
        scrollTo(str(R.string.cloud_sign_in))
        composeRule.onNodeWithText(str(R.string.cloud_sign_in)).performClick()
    }

    private fun waitForText(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aSuccessfulSignInShowsTheAccountAndThatTheCloudLetsItIn() {
        auth.onSignIn = { CloudAccount("uid-1", "owner@example.com") }
        showSettings()
        tapSignIn()

        waitForText("owner@example.com")
        scrollTo(str(R.string.cloud_sign_out))
        waitForText(str(R.string.cloud_access_ok))
        composeRule.onNodeWithText(str(R.string.cloud_sign_out)).assertExists()
        assertTrue(composeRule.onAllNodesWithText(str(R.string.cloud_sign_in)).fetchSemanticsNodes().isEmpty())
        waitFor { str(R.string.cloud_signed_in, "owner@example.com") in shownMessages }
    }

    @Test
    fun closingGooglesSheetChangesNothingAndSaysNothing() {
        auth.onSignIn = { null }
        showSettings()
        tapSignIn()

        waitFor { auth.signInCalls == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.cloud_sign_in_summary)).assertExists()
        assertEquals(emptyList<String>(), shownMessages)
    }

    @Test
    fun googleRefusingTheAppSaysWhyWithTheCodeTheAppAndItsKey() {
        val code = "GetCredentialCancellationException: 16: Account reauth failed."
        auth.onSignIn = {
            throw SignInException(SignInException.Reason.SETUP, code, null, "com.vkurylo.shifttracker.debug", "0E:53:EF:F6")
        }
        showSettings()
        tapSignIn()

        val expected = str(R.string.cloud_sign_in_setup, code, "com.vkurylo.shifttracker.debug", "0E:53:EF:F6")
        waitForText(expected) // in the row, until the next attempt
        waitFor { expected in shownMessages }
        assertTrue(expected.contains(code))
    }

    @Test
    fun anyOtherFailureShowsItsCodeAndTheNextSuccessClearsIt() {
        auth.onSignIn = { throw IllegalStateException("boom") }
        showSettings()
        tapSignIn()

        val expected = str(R.string.cloud_sign_in_failed, "IllegalStateException: boom")
        waitForText(expected)
        waitFor { expected in shownMessages }

        auth.onSignIn = { CloudAccount("uid-1", "owner@example.com") }
        tapSignIn()
        waitForText("owner@example.com")
        assertTrue(composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun eachKindOfFailureHasItsOwnMessageWithTheCode() {
        showSettings()
        val cases = listOf(
            SignInException(SignInException.Reason.NO_ACCOUNT, "NoCredentialException: none") to
                str(R.string.cloud_sign_in_no_account, "NoCredentialException: none"),
            SignInException(SignInException.Reason.NETWORK, "FirebaseNetworkException: timeout") to
                str(R.string.cloud_sign_in_offline, "FirebaseNetworkException: timeout"),
            SignInException(SignInException.Reason.OTHER, "ERROR_INVALID_CREDENTIAL: bad token") to
                str(R.string.cloud_sign_in_failed, "ERROR_INVALID_CREDENTIAL: bad token"),
        )
        for ((failure, expected) in cases) {
            auth.onSignIn = { throw failure }
            tapSignIn()
            waitForText(expected)
        }
    }
}
