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

    private fun bonus(n: Int, job: Int, amount: Long = 5000) = RemoteBonus(id(n), 1, false, id(job), 20_400, amount, null)

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
}
