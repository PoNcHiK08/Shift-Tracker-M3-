package com.vkurylo.shifttracker.data.sync.firebase

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.vkurylo.shifttracker.data.sync.CloudAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Google account cloud sync uses. Signing out only forgets the account: the data on the phone stays.
 */
interface CloudAuth {
    /** Cloud sync is set up in this build (see [FirebaseServices.isConfigured]). */
    val isAvailable: Boolean

    /** The signed-in account, or null. Always null when cloud sync isn't set up in this build. */
    val account: Flow<CloudAccount?>

    fun currentAccount(): CloudAccount?

    /**
     * Shows Google's account sheet over [activityContext] (an Activity) and signs in with the chosen account.
     * Returns null only when the user closed the sheet; anything else that goes wrong throws
     * [SignInException], already logged under [SIGN_IN_LOG_TAG].
     */
    suspend fun signIn(activityContext: Context): CloudAccount?

    suspend fun signOut()

    /**
     * Deletes the signed-in account itself (not its cloud data: delete that first) and signs out. Throws
     * [RecentLoginRequiredException] when Firebase wants a fresh sign-in first: [reauthenticate], then call again.
     */
    suspend fun deleteUser()

    /**
     * Shows Google's account sheet over [activityContext] again and refreshes the signed-in account's sign-in, for
     * [deleteUser]. False when the user closed the sheet; throws [WrongAccountException] when they picked another
     * account (nothing changes then), and [SignInException] for anything else.
     */
    suspend fun reauthenticate(activityContext: Context): Boolean
}

/** Firebase deletes an account only shortly after signing in to it; [CloudAuth.reauthenticate] first. */
class RecentLoginRequiredException(cause: Throwable?) : Exception("Firebase wants a recent sign-in to delete the account", cause)

/** Signing in again picked another Google account than the signed-in one. */
class WrongAccountException(cause: Throwable? = null) : Exception("Another Google account was picked", cause)

/** Gets a Google ID token from Android's account sheet. Separate from [FirebaseCloudAuth] so tests can stand in for it. */
fun interface GoogleIdTokenSource {
    /**
     * The ID token of the account the user picks in the sheet shown over [activityContext]. Throws what Credential
     * Manager throws (GetCredentialException, ...), including when the user closes the sheet.
     */
    suspend fun idToken(activityContext: Context, serverClientId: String): String
}

/** Credential Manager with the "Sign in with Google" option (the account sheet, which can also add an account). */
class CredentialManagerTokenSource @Inject constructor() : GoogleIdTokenSource {
    override suspend fun idToken(activityContext: Context, serverClientId: String): String {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw UnexpectedCredentialException(credential.type)
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}

/** [CloudAuth] on Firebase Auth: a Google ID token from [GoogleIdTokenSource], then Firebase signs in with it. */
@Singleton
class FirebaseCloudAuth internal constructor(
    private val context: Context,
    private val configured: () -> Boolean,
    private val webClientId: () -> String?,
    private val auth: () -> FirebaseAuth,
    private val tokens: GoogleIdTokenSource,
) : CloudAuth {

    @Inject constructor(
        @ApplicationContext context: Context,
        firebase: FirebaseServices,
        tokens: CredentialManagerTokenSource,
    ) : this(context, { firebase.isConfigured }, { firebase.webClientId }, { firebase.auth }, tokens)

    override val isAvailable: Boolean get() = configured()

    override val account: Flow<CloudAccount?> = flow {
        if (!configured()) {
            emit(null)
            return@flow
        }
        emitAll(
            callbackFlow {
                // Called right away with the current user, then on every sign-in and sign-out.
                val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccount()) }
                auth().addAuthStateListener(listener)
                awaitClose { auth().removeAuthStateListener(listener) }
            },
        )
    }.distinctUntilChanged()

    override fun currentAccount(): CloudAccount? = if (configured()) auth().currentUser?.toAccount() else null

    override suspend fun signIn(activityContext: Context): CloudAccount? {
        val idToken = googleIdToken(activityContext) ?: return null
        return signInWithGoogleIdToken(idToken)
    }

    /** A Google ID token from the account sheet, or null when the user closed it. */
    private suspend fun googleIdToken(activityContext: Context): String? {
        val clientId = webClientId()
        if (!configured() || clientId == null) {
            throw logged("setup", SignInException(SignInException.Reason.OTHER, "NOT_CONFIGURED: this build has no google-services.json"))
        }
        return try {
            tokens.idToken(activityContext, clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SignInFailures.isUserClosingTheSheet(e)) {
                Log.e(SIGN_IN_LOG_TAG, "Credential Manager: the user closed the account sheet (not an error, nothing shown)", e)
                return null
            }
            throw logged("Credential Manager", SignInFailures.fromCredentialManager(e))
        }
    }

    /** The Firebase half of [signIn]: signs in with a Google ID token. */
    internal suspend fun signInWithGoogleIdToken(idToken: String): CloudAccount {
        val user = try {
            auth().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw logged("Firebase", SignInFailures.fromFirebase(e))
        }
        return user?.toAccount()
            ?: throw logged("Firebase", SignInException(SignInException.Reason.OTHER, "NO_USER: Firebase signed in without a user"))
    }

    override suspend fun signOut() {
        if (!configured()) return
        auth().signOut()
        forgetAccountChoice()
    }

    override suspend fun deleteUser() {
        if (!configured()) return
        val user = auth().currentUser ?: return
        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw RecentLoginRequiredException(e)
        }
        // Firebase has signed out of the deleted account; make sure, and forget the account choice too.
        auth().signOut()
        forgetAccountChoice()
    }

    override suspend fun reauthenticate(activityContext: Context): Boolean {
        val user = auth().currentUser ?: throw IllegalStateException("Not signed in")
        val idToken = googleIdToken(activityContext) ?: return false
        try {
            user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_USER_MISMATCH") throw WrongAccountException(e)
            throw logged("Firebase", SignInFailures.fromFirebase(e))
        } catch (e: Exception) {
            throw logged("Firebase", SignInFailures.fromFirebase(e))
        }
        return true
    }

    /**
     * Forgets the account choice, so the next sign-in asks which account. The sign-out (or deletion) is already
     * done, so this never holds it up: it gives up after [FORGET_CHOICE_TIMEOUT_MS] (a provider that never
     * answers would otherwise leave "Deleting…" on screen for good).
     */
    private suspend fun forgetAccountChoice() {
        try {
            val done = withTimeoutOrNull(FORGET_CHOICE_TIMEOUT_MS) {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            }
            if (done == null) Log.e(SIGN_IN_LOG_TAG, "Credential Manager: clearing the account choice didn't answer (the sign-out itself is done)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SIGN_IN_LOG_TAG, "Credential Manager: clearing the account choice failed (the sign-out itself is done)", e)
        }
    }

    private companion object {
        const val FORGET_CHOICE_TIMEOUT_MS = 5_000L
    }

    /** Logs [failure] with what Google needs to recognise this app, and adds that to it for the message. */
    private fun logged(stage: String, failure: SignInException): SignInException {
        val packageName = context.packageName
        val sha1 = signingSha1(context)
        Log.e(
            SIGN_IN_LOG_TAG,
            "Sign-in failed in $stage: ${failure.code} | reason ${failure.reason} | package $packageName | " +
                "signing SHA-1 $sha1 | web client ${webClientId()}",
            failure.cause ?: failure,
        )
        return SignInException(failure.reason, failure.code, failure.cause, packageName, sha1)
    }

    private fun FirebaseUser.toAccount() = CloudAccount(uid = uid, email = email)
}

/** SHA-1 of the certificate this app is signed with, as Firebase shows fingerprints (AA:BB:...), or null. */
internal fun signingSha1(context: Context): String? = try {
    val pm = context.packageManager
    val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // The certificate that signed this APK (the one Google checks against the registered SHA-1).
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }
    certificate?.toByteArray()?.let { bytes ->
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { "%02X".format(it) }
    }
} catch (e: Exception) {
    null
}
