package com.vkurylo.shifttracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CrashReporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun reportIsShownOnceWithTraceAndContext() {
        CrashReporter.currentScreen = "EditShiftRoute"
        CrashReporter.record(context, "Crash on thread \"main\"", IllegalStateException("boom"))

        val report = CrashReporter.consume(context)!!
        assertTrue(report.contains("Crash on thread \"main\""))
        assertTrue(report.contains("Screen: EditShiftRoute"))
        assertTrue(report.contains("IllegalStateException: boom"))
        assertTrue(report.contains("CrashReporterTest"))
        assertNull(CrashReporter.consume(context))
    }
}
