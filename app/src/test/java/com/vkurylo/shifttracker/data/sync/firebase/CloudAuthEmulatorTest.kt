package com.vkurylo.shifttracker.data.sync.firebase

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.ui.settings.CloudAccess
import com.vkurylo.shifttracker.ui.settings.CloudUiState
import com.vkurylo.shifttracker.ui.settings.settingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [FirebaseCloudAuth] with the real Firebase Auth SDK on the Auth emulator (Credential Manager replaced by a token
 * source): a successful sign-in is what the account flow and Settings show, and a token Firebase rejects comes
 * back with Firebase's own code. Skipped unless the emulators run (firebase/run-emulator-tests.sh).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CloudAuthEmulatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var app: FirebaseApp
    private val databases = mutableListOf<ShiftDatabase>()

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not running", Emulators.running)
        Emulators.clear()
        app = Emulators.app()
        FirebaseAuth.getInstance(app).signOut()
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        if (::app.isInitialized) FirebaseAuth.getInstance(app).signOut()
    }

    private fun cloudAuth(tokens: GoogleIdTokenSource) =
        FirebaseCloudAuth(context, { true }, { "web-client-id" }, { FirebaseAuth.getInstance(app) }, tokens)

    @Test
    fun aSuccessfulSignInShowsTheAccountInSettingsAndTheCloudLetsItIn() {
        val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).build().also { databases += it }
        val viewModel = settingsViewModel(
            context,
            db,
            cloudAuth { _, clientId ->
                assertEquals("web-client-id", clientId)
                Emulators.googleIdToken()
            },
            FirestoreRemoteStore { FirebaseFirestore.getInstance(app) },
        )
        val states = mutableListOf<CloudUiState>()
        val screen = CoroutineScope(Dispatchers.Main).launch { viewModel.cloud.collect { states += it } }
        Emulators.idleUntil { states.isNotEmpty() }
        assertNull(states.last().account)

        viewModel.signIn(Activity())
        // The row: the account's email, then the result of reading the account's marker through the rules.
        Emulators.idleUntil { states.last().access == CloudAccess.OK }
        assertEquals(Emulators.EMAIL, states.last().account?.email)
        assertNull(states.last().signInError)
        assertTrue(states.any { it.signingIn })
        assertTrue(!states.last().signingIn)

        viewModel.signOut()
        Emulators.idleUntil { states.last().account == null }
        screen.cancel()
    }

    @Test
    fun aTokenFirebaseRejectsComesBackWithFirebasesCode() {
        val auth = cloudAuth { _, _ -> "not-a-google-id-token" }
        val activity = Activity()
        val failure = Emulators.onBackground { runCatching { auth.signIn(activity) }.exceptionOrNull() }
        failure as SignInException
        assertEquals(SignInException.Reason.OTHER, failure.reason)
        assertTrue(failure.code, failure.code.isNotBlank() && !failure.code.startsWith("SignInException"))
        assertEquals(context.packageName, failure.packageName)
        assertNull(FirebaseAuth.getInstance(app).currentUser)
        println("Firebase rejected the token with: ${failure.code}")
    }
}
