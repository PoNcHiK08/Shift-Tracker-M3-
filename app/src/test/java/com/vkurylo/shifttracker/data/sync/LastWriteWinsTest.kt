package com.vkurylo.shifttracker.data.sync

import com.vkurylo.shifttracker.data.sync.LastWriteWins.Winner
import org.junit.Assert.assertEquals
import org.junit.Test

class LastWriteWinsTest {

    @Test
    fun `the later change wins`() {
        assertEquals(Winner.LOCAL, LastWriteWins.winner(localUpdatedAt = 200, remoteUpdatedAt = 100, sameContent = false))
        assertEquals(Winner.REMOTE, LastWriteWins.winner(localUpdatedAt = 100, remoteUpdatedAt = 200, sameContent = false))
    }

    @Test
    fun `the same version changes nothing`() {
        assertEquals(Winner.SAME, LastWriteWins.winner(localUpdatedAt = 100, remoteUpdatedAt = 100, sameContent = true))
    }

    @Test
    fun `a tie with different content goes to the cloud, so every phone keeps the same version`() {
        assertEquals(Winner.REMOTE, LastWriteWins.winner(localUpdatedAt = 100, remoteUpdatedAt = 100, sameContent = false))
    }

    @Test
    fun `the same content with different times still follows the later time`() {
        assertEquals(Winner.LOCAL, LastWriteWins.winner(localUpdatedAt = 300, remoteUpdatedAt = 100, sameContent = true))
        assertEquals(Winner.REMOTE, LastWriteWins.winner(localUpdatedAt = 100, remoteUpdatedAt = 300, sameContent = true))
    }
}
