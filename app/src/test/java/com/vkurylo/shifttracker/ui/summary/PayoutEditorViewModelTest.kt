package com.vkurylo.shifttracker.ui.summary

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.TextLimits
import com.vkurylo.shifttracker.ui.FakePayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/** The form for one received payment: what it accepts, what it saves, and how Delete and Undo behave. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PayoutEditorViewModelTest {
    private val november = YearMonth.of(2026, 11)
    private val cafe = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3000, color = 1)
    private val yen = Job(id = 2, name = "Tokyo", hourlyRateGrosze = 1000, color = 2, currency = "JPY")
    private val repository = FakePayoutRepository(listOf(cafe, yen))
    private val editor = PayoutEditorViewModel(repository, CoroutineScope(Dispatchers.Unconfined))

    /** Lets the view model's coroutine (on the main looper) run, then waits for [condition]. */
    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", condition())
    }

    private fun saved(job: Job = cafe, amount: String, month: YearMonth = november): Boolean {
        editor.startNew(job, month)
        editor.setAmount(amount)
        editor.save()
        shadowOf(Looper.getMainLooper()).idle()
        return repository.upserts.isNotEmpty() && editor.draft.value == null
    }

    @Test
    fun aValidPaymentIsSavedForTheJobAndMonthItWasOpenedFor() {
        editor.startNew(cafe, november)
        editor.setAmount("1 234,56")
        editor.setReceivedOn(LocalDate.of(2026, 12, 2))
        editor.setNote("  Przelew  ")
        editor.save()
        settle { repository.upserts.isNotEmpty() }

        assertEquals(
            Payout(id = 0, jobId = 1, month = november, amountMinor = 123_456, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 2)),
            repository.upserts.single(),
        )
        assertNull("the form closes", editor.draft.value)
    }

    @Test
    fun aBlankNoteAndNoReceivedDateAreStoredAsNothing() {
        editor.startNew(cafe, november)
        editor.setAmount("50")
        editor.setNote("   ")
        editor.save()
        settle { repository.upserts.isNotEmpty() }

        assertNull(repository.upserts.single().note)
        assertNull(repository.upserts.single().receivedOn)
    }

    @Test
    fun anAmountThatIsNotAPositiveMoneyAmountIsNotSavedAndTheFormShowsTheError() {
        for (bad in listOf("", "  ", "0", "0,00", "-5", "abc", "12,345", "1.2.3", "12345678901", "5 zł")) {
            assertFalse(bad, saved(amount = bad))
            assertTrue(bad, editor.draft.value!!.showErrors)
            assertNull(bad, editor.draft.value!!.amountMinor)
        }
        assertTrue(repository.upserts.isEmpty())
    }

    @Test
    fun theAmountLimitIsTheSameAsABonus() {
        val limit = MoneyParser.maxMinor(2, MoneyParser.AMOUNT_WHOLE_DIGITS)
        assertTrue(saved(amount = MoneyParser.toInput(limit, 2, '.')))
        assertEquals(limit, repository.upserts.last().amountMinor)
        assertTrue(saved(amount = "0,01"))
        assertEquals(1L, repository.upserts.last().amountMinor)
    }

    @Test
    fun theJobsCurrencyDecidesTheDecimals() {
        assertTrue(saved(yen, "15000"))
        assertEquals(15_000L, repository.upserts.last().amountMinor)
        assertFalse("no decimals in yen", saved(yen, "150,5"))
    }

    @Test
    fun aNoteCannotGrowPastTheLimit() {
        editor.startNew(cafe, november)
        editor.setNote("x".repeat(TextLimits.MAX_LENGTH))
        editor.setNote("x".repeat(TextLimits.MAX_LENGTH + 50))
        assertEquals(TextLimits.MAX_LENGTH, editor.draft.value!!.note.length)
        assertFalse(editor.draft.value!!.noteTooLong)
    }

    @Test
    fun theMonthStepsButStaysInTheYearsTheCloudAccepts() {
        editor.startNew(cafe, YearMonth.of(Payout.MAX_YEAR, 11))
        editor.setMonth(YearMonth.of(Payout.MAX_YEAR, 12))
        assertEquals(YearMonth.of(Payout.MAX_YEAR, 12), editor.draft.value!!.month)
        editor.setMonth(YearMonth.of(Payout.MAX_YEAR + 1, 1))
        assertEquals(YearMonth.of(Payout.MAX_YEAR, 12), editor.draft.value!!.month)

        editor.startNew(cafe, YearMonth.of(Payout.MIN_YEAR, 2))
        editor.setMonth(YearMonth.of(Payout.MIN_YEAR - 1, 12))
        assertEquals(YearMonth.of(Payout.MIN_YEAR, 2), editor.draft.value!!.month)
    }

    @Test
    fun cancelClosesTheFormWithoutSaving() {
        editor.startNew(cafe, november)
        editor.setAmount("100")
        editor.cancel()
        assertNull(editor.draft.value)
        assertTrue(repository.upserts.isEmpty())
    }

    @Test
    fun editingKeepsTheRecordAndCanMoveItToAnotherMonth() = runBlocking {
        val id = repository.upsert(Payout(jobId = 1, month = november, amountMinor = 60_000, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 2)))
        val stored = repository.getPayout(id)!!

        editor.startEdit(stored, cafe)
        assertEquals("600", editor.draft.value!!.amount)
        assertFalse(editor.draft.value!!.isNew)
        editor.setAmount("650,50")
        editor.setMonth(november.plusMonths(1))
        editor.setReceivedOn(null)
        editor.save()
        settle { repository.upserts.size == 2 }

        assertEquals(Payout(id = id, jobId = 1, month = november.plusMonths(1), amountMinor = 65_050, note = "Przelew", receivedOn = null), repository.upserts.last())
        assertEquals(1, repository.all.size)
    }

    @Test
    fun deleteRemovesTheStoredPaymentAndUndoBringsBackExactlyThat() = runBlocking {
        val id = repository.upsert(Payout(jobId = 1, month = november, amountMinor = 60_000, note = "Przelew"))
        val stored = repository.getPayout(id)!!

        editor.startEdit(stored, cafe)
        editor.setAmount("1") // an unsaved edit must not be what Delete removes or Undo restores
        editor.delete()
        settle { repository.deletes.isNotEmpty() }

        assertEquals(stored, repository.deletes.single())
        assertTrue(repository.all.isEmpty())
        assertNull(editor.draft.value)
        val event = editor.events.first()
        assertEquals(PayoutEvent.Deleted(stored), event)

        editor.undoDelete((event as PayoutEvent.Deleted).payout)
        assertEquals(listOf(stored), repository.all)
    }

    @Test
    fun deleteDoesNothingForAPaymentThatWasNeverSaved() {
        editor.startNew(cafe, november)
        editor.delete()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(editor.draft.value)
        assertTrue(repository.deletes.isEmpty())
    }
}
