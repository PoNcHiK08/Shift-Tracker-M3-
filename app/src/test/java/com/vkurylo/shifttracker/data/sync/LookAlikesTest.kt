package com.vkurylo.shifttracker.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookAlikesTest {

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun job(n: Int, name: String, currency: String = "PLN", deleted: Boolean = false) =
        RemoteJob(id(n), 1, deleted, name, 3300, 1, null, currency, emptyList())

    private fun shift(n: Int, job: Int, day: Long = 20_400, start: Int = 480, end: Int = 960, deleted: Boolean = false) =
        RemoteShift(id(n), 1, deleted, id(job), day, start, end, 0, 3300, null, null, null)

    private fun bonus(n: Int, job: Int, amount: Long = 5000, wholeMonth: Boolean = false) =
        RemoteBonus(id(n), 1, false, id(job), 20_400, amount, null, wholeMonth = wholeMonth)

    private fun payout(n: Int, job: Int, month: Int = 202_611, amount: Long = 450_000) =
        RemotePayout(id(n), 1, false, id(job), month, amount, null, null)

    @Test
    fun `jobs with the same name and currency are linked, and their entries through them`() {
        val cloud = RemoteChanges(
            jobs = listOf(job(1, "Kawiarnia"), job(2, "Biuro", "EUR")),
            shifts = listOf(shift(10, job = 1), shift(11, job = 1, day = 20_401)),
            bonuses = listOf(bonus(20, job = 1)),
        )
        val links = LookAlikes.find(
            localJobs = listOf(job(101, "  kawiarnia "), job(102, "Biuro", "PLN")), // different currency: not Biuro
            localShifts = listOf(shift(110, job = 101), shift(111, job = 101, day = 20_402)),
            localBonuses = listOf(bonus(120, job = 101), bonus(121, job = 101, amount = 1)),
            cloud = cloud,
        )
        assertEquals(mapOf(id(101) to id(1)), links.jobs)
        assertEquals(mapOf(id(110) to id(10)), links.shifts)
        assertEquals(mapOf(id(120) to id(20)), links.bonuses)
        assertEquals(3, links.size)
    }

    @Test
    fun `each cloud record is linked at most once`() {
        val cloud = RemoteChanges(jobs = listOf(job(1, "Sklep")), shifts = listOf(shift(10, job = 1)))
        val links = LookAlikes.find(
            localJobs = listOf(job(101, "Sklep")),
            localShifts = listOf(shift(110, job = 101), shift(111, job = 101)), // two identical local shifts
            localBonuses = emptyList(),
            cloud = cloud,
        )
        assertEquals(1, links.shifts.size)
    }

    @Test
    fun `records already shared and deleted records are left alone`() {
        val cloud = RemoteChanges(
            jobs = listOf(job(1, "Sklep"), job(2, "Stary", deleted = true)),
            shifts = listOf(shift(10, job = 1)),
        )
        val links = LookAlikes.find(
            localJobs = listOf(job(1, "Sklep"), job(102, "Stary")),
            localShifts = listOf(shift(10, job = 1), shift(110, job = 1, deleted = true)),
            localBonuses = emptyList(),
            cloud = cloud,
        )
        assertTrue(links.jobs.isEmpty())
        assertTrue(links.shifts.isEmpty())
    }

    @Test
    fun `payouts are linked by job, month and amount`() {
        val cloud = RemoteChanges(
            jobs = listOf(job(1, "Kawiarnia")),
            payouts = listOf(payout(30, job = 1), payout(31, job = 1, month = 202_610), payout(32, job = 1, amount = 1)),
        )
        val links = LookAlikes.find(
            localJobs = listOf(job(101, "Kawiarnia")),
            localShifts = emptyList(),
            localBonuses = emptyList(),
            cloud = cloud,
            localPayouts = listOf(payout(130, job = 101), payout(131, job = 101, month = 202_612)),
        )
        assertEquals(mapOf(id(130) to id(30)), links.payouts)
        assertEquals(2, links.size) // the job and the payout
    }

    @Test
    fun `a bonus for the whole month is not a look-alike of a bonus on a day`() {
        val cloud = RemoteChanges(jobs = listOf(job(1, "Sklep")), bonuses = listOf(bonus(20, job = 1, wholeMonth = true)))
        val links = LookAlikes.find(
            localJobs = listOf(job(101, "Sklep")),
            localShifts = emptyList(),
            localBonuses = listOf(bonus(120, job = 101), bonus(121, job = 101, wholeMonth = true)),
            cloud = cloud,
        )
        assertEquals(mapOf(id(121) to id(20)), links.bonuses)
    }
}
