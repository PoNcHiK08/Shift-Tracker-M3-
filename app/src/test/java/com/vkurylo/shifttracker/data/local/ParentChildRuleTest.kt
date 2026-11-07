package com.vkurylo.shifttracker.data.local

import com.vkurylo.shifttracker.data.local.ParentChildRule.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** The decision part of [ParentChildRule]; SoftDeleteTest covers it applied to the database. */
class ParentChildRuleTest {

    @Test
    fun `a live job is left alone`() {
        assertEquals(Resolution.None, ParentChildRule.resolve(jobDeleted = false, jobUpdatedAt = 100, liveEntryUpdatedAts = listOf(50, 150)))
    }

    @Test
    fun `a deleted job without live entries is left alone`() {
        assertEquals(Resolution.None, ParentChildRule.resolve(jobDeleted = true, jobUpdatedAt = 100, liveEntryUpdatedAts = emptyList()))
    }

    @Test
    fun `an entry changed after the deletion revives the job at that entry's time`() {
        // Phone A deleted the job at 100; offline phone B added a shift at 150.
        assertEquals(Resolution.ReviveJob(150), ParentChildRule.resolve(jobDeleted = true, jobUpdatedAt = 100, liveEntryUpdatedAts = listOf(150)))
        // An older live entry doesn't stop the revival; the newest one decides.
        assertEquals(Resolution.ReviveJob(150), ParentChildRule.resolve(jobDeleted = true, jobUpdatedAt = 100, liveEntryUpdatedAts = listOf(40, 150, 90)))
    }

    @Test
    fun `entries not newer than the deletion are deleted with the job`() {
        // Phone B added a shift at 50, before phone A (which didn't know it) deleted the job at 100.
        assertEquals(Resolution.DeleteEntries(100), ParentChildRule.resolve(jobDeleted = true, jobUpdatedAt = 100, liveEntryUpdatedAts = listOf(50)))
        // The same time is not "after" the deletion.
        assertEquals(Resolution.DeleteEntries(100), ParentChildRule.resolve(jobDeleted = true, jobUpdatedAt = 100, liveEntryUpdatedAts = listOf(100, 20)))
    }

    @Test
    fun `the result depends only on the records, so every phone reaches the same one`() {
        val inputs = listOf(listOf(150L, 40L), listOf(40L, 150L))
        assertEquals(1, inputs.map { ParentChildRule.resolve(true, 100, it) }.toSet().size)
    }
}
