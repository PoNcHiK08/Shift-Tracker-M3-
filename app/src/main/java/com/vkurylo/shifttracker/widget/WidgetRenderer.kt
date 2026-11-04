package com.vkurylo.shifttracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose

/**
 * Draws the widget straight into AppWidgetManager: composes [ShiftTrackerWidget] into RemoteViews
 * (for every responsive size) and hands them over. Glance normally draws in a WorkManager job; that
 * job goes through JobScheduler, which drops jobs it rejects without a trace (the widget then stays on
 * its loading layout). Drawing here doesn't depend on it, so the widget always shows its content.
 * Its taps are plain activity PendingIntents, so they don't need Glance's per-widget state.
 */
@OptIn(ExperimentalGlanceApi::class)
object WidgetRenderer {

    suspend fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ShiftTrackerWidgetReceiver::class.java))
        ids.forEach { render(context, manager, it) }
    }

    suspend fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val widget = ShiftTrackerWidget()
        val options = manager.getAppWidgetOptions(appWidgetId) ?: Bundle()
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the layout that fits, as Glance's SizeMode.Responsive does.
            RemoteViews(
                ShiftTrackerWidget.SIZES.associate { size ->
                    SizeF(size.width.value, size.height.value) to widget.compose(context, options = options, size = size)
                },
            )
        } else {
            widget.compose(context, options = options, size = fittingSize(options))
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    /** Before Android 12: the size the widget has in portrait (its min width, max height). */
    private fun fittingSize(options: Bundle): DpSize {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).dp
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).dp
        val medium = ShiftTrackerWidget.MEDIUM
        return if (width >= medium.width && height >= medium.height) medium else ShiftTrackerWidget.COMPACT
    }
}
