package com.vkurylo.shifttracker.ui

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.repository.PayoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.YearMonth

/** Payouts in memory, like the Room repository: a delete keeps the row's place so Undo (an upsert) brings it back. */
class FakePayoutRepository(private val jobs: List<Job>, initial: List<Payout> = emptyList()) : PayoutRepository {
    private val live = MutableStateFlow(initial)
    private val deleted = mutableMapOf<Long, Payout>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    /** Every call, in order, to check what the UI asked for. */
    val upserts = mutableListOf<Payout>()
    val deletes = mutableListOf<Payout>()

    val all: List<Payout> get() = live.value

    override fun observePayouts(from: YearMonth, to: YearMonth): Flow<List<PayoutWithJob>> = live.map { list ->
        list.filter { !it.month.isBefore(from) && !it.month.isAfter(to) }
            .sortedWith(compareBy({ it.month }, { it.id }))
            .mapNotNull { payout -> jobs.firstOrNull { it.id == payout.jobId }?.let { PayoutWithJob(payout, it) } }
    }

    override suspend fun getPayout(id: Long): Payout? = live.value.firstOrNull { it.id == id }

    override suspend fun upsert(payout: Payout): Long {
        upserts += payout
        val id = if (payout.id == 0L) nextId++ else payout.id
        val stored = payout.copy(id = id)
        deleted.remove(id)
        live.value = live.value.filterNot { it.id == id } + stored
        return id
    }

    override suspend fun delete(payout: Payout) {
        deletes += payout
        deleted[payout.id] = payout
        live.value = live.value.filterNot { it.id == payout.id }
    }
}
