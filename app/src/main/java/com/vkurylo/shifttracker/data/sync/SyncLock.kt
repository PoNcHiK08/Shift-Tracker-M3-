package com.vkurylo.shifttracker.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One at a time: a sync (or linking an account) and a JSON import never overlap. A sync waits for an import to
 * finish and an import waits for a running sync, so a sync never sees, or uploads, half of an import.
 */
@Singleton
class SyncLock @Inject constructor() {
    val mutex = Mutex()
}
