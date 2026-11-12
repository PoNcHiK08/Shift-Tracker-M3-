package com.vkurylo.shifttracker.data.sync.firebase

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

/** Logcat tag of every sign-in failure: `adb logcat -s CloudSignIn`. */
const val SIGN_IN_LOG_TAG = "CloudSignIn"

/**
 * Signing in failed. [code] says exactly what failed (the exception and its message, e.g.
 * "GetCredentialCancellationException: 16: Account reauth failed."), for the user to read and report.
 * [packageName] and [signingSha1] identify this app as Google sees it, for [Reason.SETUP].
 */
class SignInException(
    val reason: Reason,
    val code: String,
    cause: Throwable? = null,
    val packageName: String? = null,
    val signingSha1: String? = null,
) : Exception(code, cause) {
    enum class Reason {
        /** No Google account on the phone (and none was added). */
        NO_ACCOUNT,

        /** No connection. */
        NETWORK,

        /** Google refused this app: most often the app's SHA-1 fingerprint isn't registered in Firebase. */
        SETUP,

        /** Anything else; [code] says what. */
        OTHER,
    }
}

/** The Google ID token source returned a credential that isn't a Google ID token. */
class UnexpectedCredentialException(type: String) : Exception("Unexpected credential type: $type")

/** Maps everything that can go wrong while signing in to what the user is shown. */
internal object SignInFailures {

    /**
     * Credential Manager's message when the user closes Google's account sheet themselves
     * (androidx.credentials.playservices CredentialProviderController). Google refusing the sign-in after an
     * account was picked is reported with the same exception type, GetCredentialCancellationException, but with
     * Google's status as the message (e.g. "16: Account reauth failed."), so only this exact text counts as the
     * user's own choice.
     */
    const val USER_CLOSED_THE_SHEET = "activity is cancelled by the user."

    /** The user closed the account sheet: nothing went wrong, nothing to show. */
    fun isUserClosingTheSheet(e: Throwable): Boolean =
        e is GetCredentialCancellationException && e.errorMessage?.toString() == USER_CLOSED_THE_SHEET

    /** A failure while getting the Google ID token (Credential Manager, Google Play services). */
    fun fromCredentialManager(e: Throwable): SignInException {
        val message = (e as? GetCredentialException)?.errorMessage?.toString()
        val reason = when {
            e is NoCredentialException -> SignInException.Reason.NO_ACCOUNT
            e is GetCredentialException && statusOf(message) == "7" -> SignInException.Reason.NETWORK // NETWORK_ERROR
            e is GetCredentialException && looksLikeSetupProblem(message) -> SignInException.Reason.SETUP
            else -> SignInException.Reason.OTHER // incl. GoogleIdTokenParsingException, UnexpectedCredentialException
        }
        return SignInException(reason, codeOf(e), e)
    }

    /** A failure while signing in to Firebase with the token. */
    fun fromFirebase(e: Throwable): SignInException = when (e) {
        is FirebaseNetworkException -> SignInException(SignInException.Reason.NETWORK, codeOf(e), e)
        is FirebaseTooManyRequestsException -> SignInException(SignInException.Reason.OTHER, codeOf(e), e)
        // errorCode is Firebase's own, e.g. ERROR_INVALID_CREDENTIAL.
        is FirebaseAuthException -> SignInException(SignInException.Reason.OTHER, "${e.errorCode}: ${e.message}", e)
        else -> SignInException(SignInException.Reason.OTHER, codeOf(e), e)
    }

    fun codeOf(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"

    /**
     * Google Play services' answers that mean the app isn't set up for Google sign-in: 10 (DEVELOPER_ERROR),
     * 16 "Account reauth failed" and 28444 "Developer console is not set up correctly". Their usual cause is a
     * SHA-1 fingerprint of the app's signing key missing in Firebase for this package.
     */
    fun looksLikeSetupProblem(message: String?): Boolean {
        if (message == null) return false
        val status = statusOf(message)
        return status == "10" || status == "28444" ||
            (status == "16" && message.contains("reauth", ignoreCase = true)) ||
            message.contains("developer console", ignoreCase = true)
    }

    /** Google Play services puts its status code first: "16: Account reauth failed.", "[28444] Developer ...". */
    private fun statusOf(message: String?): String? = message?.let { Regex("""^\[?(\d+)""").find(it.trim())?.groupValues?.get(1) }
}
