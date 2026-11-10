package com.vkurylo.shifttracker.data.sync.firebase

import android.annotation.SuppressLint
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.firestoreSettings
import com.vkurylo.shifttracker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase as this build has it. Cloud sync is optional: a build made without google-services.json has no
 * Firebase app, [isConfigured] is false, and nothing else here may be used (it throws
 * [CloudUnavailableException]). The rest of the app works the same either way.
 */
@Singleton
class FirebaseServices @Inject constructor(@ApplicationContext private val context: Context) {

    /** google-services.json was there when this build was made (with Google sign-in enabled in it). */
    val isConfigured: Boolean by lazy { FirebaseApp.getApps(context).isNotEmpty() && webClientId != null }

    /**
     * The OAuth client ("Web client", type 3 in google-services.json) Google sign-in asks an ID token for.
     * The google-services plugin generates the resource only when the file is there, so it isn't in R and is
     * looked up by name. The release build's resource shrinker can't see that: res/raw/cloud_sync_keep.xml keeps
     * it, and CI checks the APKs still have it (.github/scripts/check-apk-cloud-sync.sh).
     */
    val webClientId: String? by lazy {
        val resources = context.resources
        @SuppressLint("DiscouragedApi")
        val id = resources.getIdentifier("default_web_client_id", "string", resources.getResourcePackageName(R.string.app_name))
        if (id == 0) null else resources.getString(id).takeIf { it.isNotBlank() }
    }

    val auth: FirebaseAuth by lazy {
        requireConfigured()
        FirebaseAuth.getInstance()
    }

    val firestore: FirebaseFirestore by lazy {
        requireConfigured()
        FirebaseFirestore.getInstance().apply {
            // Room is the only copy the app reads. The cloud is only read from the server and written in
            // transactions (both online only), so Firestore keeps no copy of its own on disk.
            firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        }
    }

    private fun requireConfigured() {
        if (!isConfigured) throw CloudUnavailableException()
    }
}

/** Cloud sync isn't set up in this build (it was made without google-services.json). */
class CloudUnavailableException : IllegalStateException("Cloud sync isn't set up in this build")
