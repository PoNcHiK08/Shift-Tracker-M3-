package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One automatic sync, in the background. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncController(): SyncController
    }

    override suspend fun doWork(): Result {
        val controller = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).syncController()
        return when (controller.runAutomatic()) {
            // Offline or a passing error: again later (backoff), a few times; the next trigger starts over.
            AutomaticRun.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            AutomaticRun.DONE -> Result.success()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 6
    }
}

/**
 * [SyncScheduler] on WorkManager: one sync at a time ([WORK_NAME]; a request while one is waiting or running is
 * dropped: the change-trigger fires again when that one has uploaded), only with a network connection (so it
 * runs by itself when the network comes back), retried with exponential backoff from 30 seconds.
 */
class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {

    override fun request() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "cloud-sync"
    }
}
