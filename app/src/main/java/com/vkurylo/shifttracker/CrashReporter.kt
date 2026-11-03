package com.vkurylo.shifttracker

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the last crash (or unexpected close) so the app can show it on the next launch.
 * There is no Play Console for sideloaded APKs, so this is how a crash report reaches us:
 * the user copies the text from the dialog and sends it.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    /** Current top-level screen, for context in reports; "home" when the tabbed home is shown. */
    @Volatile
    var currentScreen: String = "home"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "Crash on thread \"${thread.name}\"", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The activity is closing while a detail screen is open — never expected, so keep who did it. */
    fun recordUnexpectedFinish(context: Context) {
        runCatching { record(context, "The app closed itself unexpectedly", Throwable("finish() was called from:")) }
    }

    fun record(context: Context, title: String, error: Throwable) {
        val text = buildString {
            appendLine(title)
            appendLine("Screen: $currentScreen")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(error.stackTraceToString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** Returns the saved report once and deletes it. */
    fun consume(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}
