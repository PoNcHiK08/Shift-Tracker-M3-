package com.vkurylo.shifttracker.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.FakeCloudAuth
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.firebase.RecentLoginRequiredException
import com.vkurylo.shifttracker.data.sync.firebase.WrongAccountException
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

/**
 * Settings → "Delete account" (Google Play asks for account deletion inside the app): it asks first, deletes the
 * account's cloud data and then the account, signs out, and leaves everything on the phone. When Google wants a
 * fresh sign-in first, it asks for one and then finishes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class SettingsDeleteAccountTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private val account = CloudAccount("uid-tester", "tester@example.com")
    private val auth = FakeCloudAuth(signedInAs = account)
    private val snackbars = SnackbarHostState()
    private val shownMessages = mutableListOf<String>()

    @After
    fun tearDown() = db.close()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun showSettings() {
        runBlocking {
            val details = JobDetailsSource(db.paySettingsDao())
            RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, WallClock.System)
                .upsert(Job(name = "Cafe", hourlyRateGrosze = 3000, color = 0))
        }
        val viewModel = settingsViewModel(context, db, auth, FakeRemoteStore())
        composeRule.setContent {
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
    }

    private fun tapDeleteAccount() {
        val row = str(R.string.account_delete)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(row))
        composeRule.onNode(hasText(row) and hasClickAction()).performClick()
    }

    /** A button in the open dialog (the row behind it can have the same words). */
    private fun dialogButton(text: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun jobsOnThePhone() = runBlocking { db.jobDao().getAll().size }

    @Test
    fun itAsksFirstThenDeletesTheAccountAndKeepsThePhonesData() {
        showSettings()

        tapDeleteAccount()
        waitForText(str(R.string.account_delete_title))
        waitForText(str(R.string.account_delete_message, account.email!!))
        dialogButton(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()
        assertEquals("cancel deletes nothing", 0, auth.deleteUserCalls)
        assertEquals(account, auth.currentAccount())

        tapDeleteAccount()
        waitForText(str(R.string.account_delete_title))
        dialogButton(str(R.string.account_delete_confirm)).performClick()

        waitFor { auth.currentAccount() == null }
        waitFor { str(R.string.account_delete_done) in shownMessages }
        assertEquals(1, auth.deleteUserCalls)
        assertEquals("the phone keeps its data", 1, jobsOnThePhone())
        // Signed out: the sign-in row is back.
        waitForText(str(R.string.cloud_sign_in))
    }

    @Test
    fun whenGoogleWantsAFreshSignInItAsksForOneThenFinishes() {
        var attempts = 0
        auth.onDeleteUser = { if (attempts++ == 0) throw RecentLoginRequiredException(null) }
        showSettings()

        tapDeleteAccount()
        waitForText(str(R.string.account_delete_title))
        dialogButton(str(R.string.account_delete_confirm)).performClick()

        waitForText(str(R.string.account_reauth_title))
        waitForText(str(R.string.account_reauth_message, account.email!!))
        assertEquals("still signed in", account, auth.currentAccount())
        dialogButton(str(R.string.account_reauth_confirm)).performClick()

        waitFor { auth.currentAccount() == null }
        assertEquals(1, auth.reauthenticateCalls)
        assertEquals(2, auth.deleteUserCalls)
        waitFor { str(R.string.account_delete_done) in shownMessages }
        assertEquals(1, jobsOnThePhone())
    }

    @Test
    fun signingInAgainWithAnotherAccountDeletesNothingAndAsksAgain() {
        auth.onDeleteUser = { throw RecentLoginRequiredException(null) }
        auth.onReauthenticate = { throw WrongAccountException() }
        showSettings()

        tapDeleteAccount()
        waitForText(str(R.string.account_delete_title))
        dialogButton(str(R.string.account_delete_confirm)).performClick()
        waitForText(str(R.string.account_reauth_title))
        dialogButton(str(R.string.account_reauth_confirm)).performClick()

        waitFor { str(R.string.account_reauth_wrong_account, account.email!!) in shownMessages }
        assertEquals("only the first attempt reached Firebase", 1, auth.deleteUserCalls)
        assertEquals(account, auth.currentAccount())
        // The question stays, so the right account can still be picked (or it can be cancelled).
        waitForText(str(R.string.account_reauth_title))
        dialogButton(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(str(R.string.account_reauth_title)).fetchSemanticsNodes().isEmpty())
        assertEquals(account, auth.currentAccount())
    }
}
