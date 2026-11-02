package com.vkurylo.shifttracker.domain.model

import java.time.LocalDate

/** A one-off extra payment (bonus, tips, premium…) for a job on a given day. */
data class Bonus(
    val id: Long = 0,
    val jobId: Long,
    val date: LocalDate,
    val amountGrosze: Long,
    val note: String? = null,
)

data class BonusWithJob(
    val bonus: Bonus,
    val job: Job,
)
