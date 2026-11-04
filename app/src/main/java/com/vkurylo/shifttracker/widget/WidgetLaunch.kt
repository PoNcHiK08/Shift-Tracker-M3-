package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vkurylo.shifttracker.MainActivity

/** What a widget tap asks the app to open. */
sealed interface LaunchRequest {
    /** The new-shift editor on today's date. */
    data object NewShift : LaunchRequest
    data class EditShift(val shiftId: Long) : LaunchRequest
    data object Summary : LaunchRequest
    data object Jobs : LaunchRequest
}

/** Intents from the widget to [MainActivity], and their parsing. */
object WidgetLaunch {
    const val ACTION = "com.vkurylo.shifttracker.WIDGET_OPEN"
    private const val EXTRA_TARGET = "target"
    private const val EXTRA_SHIFT_ID = "shiftId"

    private const val NEW_SHIFT = "new-shift"
    private const val SHIFT = "shift"
    private const val SUMMARY = "summary"
    private const val JOBS = "jobs"
    private const val APP = "app"

    /** [request] null: just open the app. Each target gets its own data URI so their PendingIntents stay apart. */
    fun intent(context: Context, request: LaunchRequest?): Intent {
        val (target, id) = when (request) {
            LaunchRequest.NewShift -> NEW_SHIFT to 0L
            is LaunchRequest.EditShift -> SHIFT to request.shiftId
            LaunchRequest.Summary -> SUMMARY to 0L
            LaunchRequest.Jobs -> JOBS to 0L
            null -> APP to 0L
        }
        return Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("shifttracker://widget/$target/$id"))
            .putExtra(EXTRA_TARGET, target)
            .putExtra(EXTRA_SHIFT_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The request carried by [intent], or null. An intent replayed from Recents (the task was finished
     * and is relaunched with its original intent) is ignored.
     */
    fun parse(intent: Intent?): LaunchRequest? {
        if (intent?.action != ACTION) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return when (intent.getStringExtra(EXTRA_TARGET)) {
            NEW_SHIFT -> LaunchRequest.NewShift
            SHIFT -> intent.getLongExtra(EXTRA_SHIFT_ID, 0L).takeIf { it > 0 }?.let { LaunchRequest.EditShift(it) }
            SUMMARY -> LaunchRequest.Summary
            JOBS -> LaunchRequest.Jobs
            else -> null
        }
    }
}
