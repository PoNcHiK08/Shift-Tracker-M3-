package com.vkurylo.shifttracker

import android.app.Application
import com.vkurylo.shifttracker.data.sync.SyncController
import com.vkurylo.shifttracker.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ShiftTrackerApp : Application() {
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var syncController: SyncController

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Keeps a placed home-screen widget in step with every change made in the app.
        widgetUpdater.start()
        // Cloud sync's local-change trigger (it does nothing until the user turns sync on).
        syncController.start()
    }
}
