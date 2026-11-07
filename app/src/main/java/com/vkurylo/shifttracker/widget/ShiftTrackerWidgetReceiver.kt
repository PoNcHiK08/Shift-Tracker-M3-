package com.vkurylo.shifttracker.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The widget's receiver. Every update from the system (placing, the 30 min period, resizing) and every
 * clock, time zone or locale change draws the widget directly ([WidgetRenderer]), besides Glance's own
 * session, so it never depends on a background job being run.
 */
class ShiftTrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShiftTrackerWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updater(context).setPlaced(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        updater(context).setPlaced(false)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        renderInBackground { appWidgetIds.forEach { WidgetRenderer.render(context, appWidgetManager, it) } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderInBackground { WidgetRenderer.render(context, appWidgetManager, appWidgetId) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in REFRESH_ACTIONS) renderInBackground { WidgetRenderer.renderAll(context) }
    }

    /** Runs [block] off the main thread, keeping the broadcast alive until it is done. */
    private fun renderInBackground(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching { block() }.onFailure { Log.e(TAG, "Widget render failed", it) }
            } finally {
                pending?.finish()
            }
        }
    }

    private fun updater(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater()

    private companion object {
        const val TAG = "ShiftTrackerWidget"
        val REFRESH_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            // Not delivered to manifest receivers on Android 8+ (not an exempt broadcast); harmless.
            Intent.ACTION_DATE_CHANGED,
        )
    }
}
