package com.vkurylo.shifttracker.data.sync.firebase

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.vkurylo.shifttracker.data.sync.CloudAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Google account cloud sync uses. Signing in takes a Google ID token from Credential Manager (Android's
 * account sheet) and signs in to Firebase Auth with it. Signing out only forgets the account: the data on
 * the phone stays as it is.
 */
@Singleton
class CloudAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebase: FirebaseServices,
) {
    /** Cloud sync is set up in this build (see [FirebaseServices.isConfigured]). */
    val isAvailable: Boolean get() = firebase.isConfigured

    /** The signed-in account, or null. Always null when cloud sync isn't set up in this build. */
    val account: Flow<CloudAccount?> = flow {
        if (!firebase.isConfigured) {
            emit(null)
            return@flow
        }
        emitAll(
            callbackFlow {
                val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccount()) }
                firebase.auth.addAuthStateListener(listener)
                awaitClose { firebase.auth.removeAuthStateListener(listener) }
            },
        )
    }.distinctUntilChanged()

    fun currentAccount(): CloudAccount? = if (firebase.isConfigured) firebase.auth.currentUser?.toAccount() else null

    /**
     * Shows Google's account sheet over [activityContext] (an Activity) and signs in with the chosen account.
     * Returns null if the user closed the sheet; throws [SignInException] if signing in failed.
     */
    suspend fun signIn(activityContext: Context): CloudAccount? {
        val clientId = firebase.webClientId ?: throw CloudUnavailableException()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw SignInException(SignInException.Reason.NO_ACCOUNT, e)
        } catch (e: GetCredentialException) {
            throw SignInException(SignInException.Reason.OTHER, e)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw SignInException(SignInException.Reason.OTHER, IllegalStateException("Unexpected credential: ${credential.type}"))
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val user = try {
            firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
        } catch (e: FirebaseNetworkException) {
            throw SignInException(SignInException.Reason.NETWORK, e)
        } catch (e: Exception) {
            throw SignInException(SignInException.Reason.OTHER, e)
        }
        return user?.toAccount() ?: throw SignInException(SignInException.Reason.OTHER, IllegalStateException("No user after sign-in"))
    }

    /** Signs out of Firebase and forgets the account choice, so the next sign-in asks which account. Local data stays. */
    suspend fun signOut() {
        if (!firebase.isConfigured) return
        firebase.auth.signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            // Only Credential Manager's memory of the last choice; the sign-out itself is done.
        }
    }

    private fun FirebaseUser.toAccount() = CloudAccount(uid = uid, email = email)
}

class SignInException(val reason: Reason, cause: Throwable) : Exception(cause.message, cause) {
    enum class Reason {
        /** No Google account on the phone (and none was added). */
        NO_ACCOUNT,

        /** No connection. */
        NETWORK,

        /** Anything else, e.g. Firebase or the app's fingerprints set up wrongly; [message] says what. */
        OTHER,
    }
}
