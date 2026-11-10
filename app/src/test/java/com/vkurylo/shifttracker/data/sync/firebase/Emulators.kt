package com.vkurylo.shifttracker.data.sync.firebase

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.UUID

/**
 * The Firebase emulators for the emulator tests (firebase/run-emulator-tests.sh starts them with firestore.rules,
 * the placeholder email replaced by [EMAIL]). Tests skip unless they run.
 */
internal object Emulators {
    const val PROJECT = "demo-shift-tracker"
    const val EMAIL = "owner@example.com"
    private const val HOST = "127.0.0.1"
    private const val APP_NAME = "emulator"

    val running: Boolean
        get() = System.getenv("FIRESTORE_EMULATOR_HOST") != null && System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null

    /** Empties both emulators (the Auth emulator gives the same email the same user, so tests would share data). */
    fun clear() {
        delete("http://${System.getenv("FIRESTORE_EMULATOR_HOST")}/emulator/v1/projects/$PROJECT/databases/(default)/documents")
        delete("http://${System.getenv("FIREBASE_AUTH_EMULATOR_HOST")}/emulator/v1/projects/$PROJECT/accounts")
    }

    private fun delete(url: String) {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "DELETE"
        check(connection.responseCode == 200) { "clearing $url: ${connection.responseCode}" }
        connection.disconnect()
    }

    /** One Firebase app for all the tests (Firebase keeps state across them), pointed at the emulators once. */
    fun app(): FirebaseApp {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT)
            .setApplicationId("1:1:android:1")
            .setApiKey("fake-api-key")
            .build()
        val app = FirebaseApp.initializeApp(context, options, APP_NAME)
        FirebaseAuth.getInstance(app).useEmulator(HOST, 9099)
        FirebaseFirestore.getInstance(app).apply {
            useEmulator(HOST, 8080)
            firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        }
        return app
    }

    /** The Auth emulator takes an unsigned Google ID token: the same claims a real Google sign-in gives. */
    fun googleIdToken(email: String = EMAIL) = """{"sub":"google-${UUID.randomUUID()}","email":"$email","email_verified":true}"""

    /**
     * Runs [block] off the main thread while the test thread keeps Robolectric's main looper going: Firebase
     * (and a ViewModel's scope) run some of their work on the main thread.
     */
    fun <T> onBackground(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { block() }
        idleUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    /** Keeps the main looper going until [condition] holds (or a minute passes). */
    fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "timed out" }
        }
    }
}
