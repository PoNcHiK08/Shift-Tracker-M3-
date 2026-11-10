package com.vkurylo.shifttracker.ui

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.TextLimits
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.bonus.EditBonusScreen
import com.vkurylo.shifttracker.ui.bonus.EditBonusViewModel
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.shift.EditShiftViewModel
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime

/**
 * Notes stop at the cloud's limit (20 000 characters), show their length near it, and a note saved before the
 * limit existed must be shortened before the entry can be saved: nothing an editor saves can hold up sync.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class EditorTextLimitTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val max = TextLimits.MAX_LENGTH
    private val job = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3050, color = 1)

    private val jobs = object : JobRepository {
        override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(job))
        override suspend fun getJob(id: Long): Job? = job
        override suspend fun upsert(job: Job): Long = job.id
        override suspend fun delete(job: Job) = Unit
        override suspend fun shiftCount(jobId: Long): Int = 0
        override suspend fun entryCount(jobId: Long): Int = 0
        override suspend fun latestCurrency(): String? = null
        override suspend fun save(job: Job, pay: PaySettings): Long = job.id
    }

    private fun count(value: Int) = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(value)

    private fun shown(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aBonusNoteOverTheLimitMustBeShortenedBeforeSavingAndThenSaves() {
        val legacy = "a".repeat(max + 500)
        val saved = mutableListOf<Bonus>()
        val bonuses = object : BonusRepository {
            override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> = flowOf(emptyList())
            override suspend fun getBonus(id: Long): Bonus = Bonus(id = 5, jobId = 1, date = LocalDate.of(2026, 11, 3), amountGrosze = 7500, note = legacy)
            override suspend fun upsert(bonus: Bonus): Long = bonus.id.also { saved += bonus }
            override suspend fun delete(bonus: Bonus) = Unit
        }
        val vm = EditBonusViewModel(SavedStateHandle(mapOf("bonusId" to 5L)), bonuses, jobs, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    EditBonusScreen(onClose = {}, onManageJobs = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }

        val tooLong = context.getString(R.string.text_too_long, count(max + 500), count(max))
        composeRule.waitUntil(5_000) { shown(tooLong) }
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.waitForIdle()
        assertTrue("not saved while too long", saved.isEmpty())

        // It can only get shorter.
        vm.setNote(legacy + "more")
        assertEquals(legacy, vm.uiState.value.note)
        vm.setNote("a".repeat(max - 100))
        composeRule.waitUntil(5_000) { shown(context.getString(R.string.text_length, count(max - 100), count(max))) }
        assertFalse(shown(tooLong))

        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(max - 100, saved.single().note!!.length)
    }

    @Test
    fun aShiftNoteStopsAtTheLimitAndShortNotesShowNoCounter() {
        var stored: Shift? = null
        val shifts = object : ShiftRepository {
            override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> = flowOf(emptyList())
            override suspend fun getShift(id: Long): Shift? = null
            override suspend fun getAllShifts(): List<Shift> = emptyList()
            override suspend fun upsert(shift: Shift): Long = 1L.also { stored = shift }
            override suspend fun importFromCalendar(shifts: List<Shift>) = Unit
            override suspend fun delete(shift: Shift) = Unit
        }
        val vm = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to 0L)), shifts, jobs, CoroutineScope(Dispatchers.Unconfined))
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }

        // Typing, or pasting, past the limit keeps the first 20 000 characters.
        vm.setNote("a".repeat(max - 1))
        vm.setNote("a".repeat(max - 1) + "bcd")
        assertEquals("a".repeat(max - 1) + "b", vm.uiState.value.note)
        assertTrue(vm.uiState.value.canSave)
        assertFalse(vm.uiState.value.noteTooLong)

        vm.setStart(LocalTime.of(8, 0))
        vm.save()
        composeRule.waitUntil(5_000) { stored != null }
        assertEquals(max, stored!!.note!!.length)

        vm.setNote("short")
        assertNull(com.vkurylo.shifttracker.ui.components.textLengthSupport(vm.uiState.value.note.length))
    }
}
