package com.vkurylo.shifttracker.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Glance draws widgets in a WorkManager job. WorkManager keeps its queue in its own Room database,
 * so it has to work with the app's Room version: with the WorkManager Glance brings by itself (2.7.1,
 * built for Room 2.2) the job never ran and the widget stayed on its loading layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkManagerCompatTest {
    class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    @Test
    fun aJobIsQueuedAndRuns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val request = OneTimeWorkRequestBuilder<NoOpWorker>().build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(request).result.get()
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(request.id).get()!!.state)
    }
}
