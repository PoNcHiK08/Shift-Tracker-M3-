package com.vkurylo.shifttracker.ui.settings

import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.sync.SyncController
import com.vkurylo.shifttracker.data.sync.SyncOutcome
import com.vkurylo.shifttracker.data.sync.SyncProblem
import com.vkurylo.shifttracker.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** What the account row says about sync. */
class SyncStatusTextTest {

    private val today = LocalDate.of(2026, 11, 10)
    private val at = today.atTime(14, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `off says nothing about sync`() {
        assertEquals(emptyList<UiMessage>(), SyncStatus(enabled = false, lastSyncedAt = at).describe(today))
    }

    @Test
    fun `a sync shows what it moved, the writes and today's total`() {
        val status = SyncStatus(enabled = true, lastSyncedAt = at, last = SyncOutcome(at, downloaded = 3, uploaded = 12), writesToday = 340)
        assertEquals(listOf(UiMessage(R.string.sync_done, listOf("14:32", 3, 12, 340))), status.describe(today))
        assertEquals("09.11, 14:32", syncTime(at - 86_400_000, today))
    }

    @Test
    fun `waiting changes and skipped records get their own lines`() {
        val status = SyncStatus(enabled = true, pending = 2, lastSyncedAt = at, last = SyncOutcome(at, skipped = 1))
        assertEquals(
            listOf(UiMessage(R.string.sync_done, listOf("14:32", 0, 0, 0)), UiMessage(R.string.sync_skipped, listOf(1)), UiMessage(R.string.sync_pending, listOf(2))),
            status.describe(today),
        )
    }

    @Test
    fun `each problem has its message`() {
        fun first(problem: SyncProblem, detail: String? = null, writes: Int = 0) =
            SyncStatus(enabled = true, lastSyncedAt = at, last = SyncOutcome(at, problem = problem, detail = detail), writesToday = writes).describe(today).first()

        assertEquals(UiMessage(R.string.sync_clock_ahead, listOf("30")), first(SyncProblem.CLOCK, "30"))
        assertEquals(UiMessage(R.string.sync_refused_check_clock), first(SyncProblem.WRITE_REFUSED_CLOCK_UNKNOWN))
        assertEquals(UiMessage(R.string.sync_refused_rules), first(SyncProblem.WRITE_REFUSED))
        assertEquals(UiMessage(R.string.sync_offline), first(SyncProblem.OFFLINE))
        assertEquals(UiMessage(R.string.cloud_access_denied), first(SyncProblem.ACCESS_DENIED))
        assertEquals(
            UiMessage(R.string.sync_budget, listOf(5_000, SyncController.DAILY_AUTOMATIC_WRITES)),
            first(SyncProblem.BUDGET, writes = 5_000),
        )
        assertEquals(UiMessage(R.string.sync_error, listOf("IllegalStateException: x")), first(SyncProblem.OTHER, "IllegalStateException: x"))
    }

    @Test
    fun `running and a deleted cloud come first`() {
        val failed = SyncOutcome(at, problem = SyncProblem.OFFLINE)
        assertEquals(listOf(UiMessage(R.string.sync_running)), SyncStatus(enabled = true, running = true, pending = 3, last = failed).describe(today))
        assertEquals(UiMessage(R.string.sync_cloud_reset), SyncStatus(enabled = true, cloudReset = true, last = failed).describe(today).first())
    }
}
