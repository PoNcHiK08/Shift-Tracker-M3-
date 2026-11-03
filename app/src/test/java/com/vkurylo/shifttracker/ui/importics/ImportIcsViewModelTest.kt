package com.vkurylo.shifttracker.ui.importics

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The import preview learns which time zones were unknown, so the screen can warn about them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportIcsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    private fun viewModelFor(ics: String): ImportIcsViewModel {
        val file = File(context.cacheDir, "schedule.ics").apply { writeText(ics) }
        return ImportIcsViewModel(
            SavedStateHandle(mapOf("uri" to Uri.fromFile(file).toString())),
            DocumentIo(context),
            RoomShiftRepository(db.shiftDao()),
            RoomJobRepository(db.jobDao()),
        )
    }

    private fun ImportIcsViewModel.awaitLoaded() {
        val deadline = System.currentTimeMillis() + 10_000
        while (uiState.value.loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("still loading", !uiState.value.loading)
    }

    @Test
    fun unknownTimeZonesReachThePreview() {
        val vm = viewModelFor(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART;TZID=Firma/Nieznana:20261102T080000
            DTEND;TZID=Firma/Nieznana:20261102T160000
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Warsaw:20261103T080000
            DTEND;TZID=Europe/Warsaw:20261103T160000
            END:VEVENT
            END:VCALENDAR
            """.trimIndent().replace("\n", "\r\n"),
        )
        vm.awaitLoaded()
        val state = vm.uiState.value
        assertEquals(false, state.error)
        assertEquals(2, state.candidates.size)
        assertEquals(listOf("Firma/Nieznana"), state.unknownZones)
    }

    @Test
    fun noWarningWhenAllZonesAreKnown() {
        val vm = viewModelFor(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261102T070000Z\r\nDTEND:20261102T150000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        )
        vm.awaitLoaded()
        assertTrue(vm.uiState.value.unknownZones.isEmpty())
        assertEquals(1, vm.uiState.value.candidates.size)
    }
}
