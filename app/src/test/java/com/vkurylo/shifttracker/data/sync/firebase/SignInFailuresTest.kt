package com.vkurylo.shifttracker.data.sync.firebase

import android.app.Activity
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.vkurylo.shifttracker.data.sync.firebase.SignInException.Reason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What each way of failing to sign in turns into: silence only when the user closed Google's sheet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SignInFailuresTest {

    @Test
    fun `only the user closing the sheet counts as cancelled`() {
        assertTrue(SignInFailures.isUserClosingTheSheet(GetCredentialCancellationException("activity is cancelled by the user.")))
        // Google refusing after an account was picked comes as the same exception type, with its status.
        assertFalse(SignInFailures.isUserClosingTheSheet(GetCredentialCancellationException("16: Account reauth failed.")))
        assertFalse(SignInFailures.isUserClosingTheSheet(GetCredentialCancellationException(null)))
        assertFalse(SignInFailures.isUserClosingTheSheet(GetCredentialUnknownException("activity is cancelled by the user.")))
    }

    @Test
    fun `Google refusing the app points at the fingerprints, with the exact code`() {
        for (message in listOf("16: Account reauth failed.", "[28444] Developer console is not set up correctly.", "10: ")) {
            val failure = SignInFailures.fromCredentialManager(GetCredentialCancellationException(message))
            assertEquals(message, Reason.SETUP, failure.reason)
            assertEquals("GetCredentialCancellationException: $message", failure.code)
        }
        assertEquals(Reason.SETUP, SignInFailures.fromCredentialManager(GetCredentialUnknownException("[28444] Developer console is not set up correctly.")).reason)
    }

    @Test
    fun `other Credential Manager failures`() {
        assertEquals(Reason.NO_ACCOUNT, SignInFailures.fromCredentialManager(NoCredentialException("no accounts")).reason)
        assertEquals(Reason.NETWORK, SignInFailures.fromCredentialManager(GetCredentialInterruptedException("7: NETWORK_ERROR")).reason)
        val other = SignInFailures.fromCredentialManager(GetCredentialUnknownException("8: INTERNAL_ERROR"))
        assertEquals(Reason.OTHER, other.reason)
        assertEquals("GetCredentialUnknownException: 8: INTERNAL_ERROR", other.code)
        val wrongType = SignInFailures.fromCredentialManager(UnexpectedCredentialException("password"))
        assertEquals(Reason.OTHER, wrongType.reason)
        assertTrue(wrongType.code.contains("password"))
    }

    @Test
    fun `Firebase failures keep Firebase's own code`() {
        assertEquals(Reason.NETWORK, SignInFailures.fromFirebase(FirebaseNetworkException("timeout")).reason)
        val invalid = SignInFailures.fromFirebase(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "The supplied auth credential is malformed."))
        assertEquals(Reason.OTHER, invalid.reason)
        assertEquals("ERROR_INVALID_CREDENTIAL: The supplied auth credential is malformed.", invalid.code)
        assertEquals("IllegalStateException: boom", SignInFailures.fromFirebase(IllegalStateException("boom")).code)
    }

    /** [FirebaseCloudAuth.signIn] up to Firebase, with Credential Manager replaced: the cases that never reach Firebase. */
    @Test
    fun `signIn is silent only when the sheet was closed, and says which app and key otherwise`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun auth(failure: Exception) = FirebaseCloudAuth(
            context = context,
            configured = { true },
            webClientId = { "web-client" },
            auth = { error("Firebase is not reached") },
            tokens = { _, clientId ->
                assertEquals("web-client", clientId)
                throw failure
            },
        )
        val activity = Activity()

        assertNull(auth(GetCredentialCancellationException("activity is cancelled by the user.")).signIn(activity))

        try {
            auth(GetCredentialCancellationException("16: Account reauth failed.")).signIn(activity)
            fail("expected a SignInException")
        } catch (e: SignInException) {
            assertEquals(Reason.SETUP, e.reason)
            assertEquals("GetCredentialCancellationException: 16: Account reauth failed.", e.code)
            assertEquals(context.packageName, e.packageName)
        }

        try {
            auth(IllegalArgumentException("unexpected")).signIn(activity)
            fail("expected a SignInException")
        } catch (e: SignInException) {
            assertEquals(Reason.OTHER, e.reason)
            assertEquals("IllegalArgumentException: unexpected", e.code)
        }
    }
}
