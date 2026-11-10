package com.vkurylo.shifttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.ui.components.CrashReportDialog
import com.vkurylo.shifttracker.ui.navigation.ShiftTrackerNavHost
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.LocalEarningsDisplay
import androidx.compose.runtime.CompositionLocalProvider
import com.vkurylo.shifttracker.ui.theme.isDark
import com.vkurylo.shifttracker.widget.LaunchRequest
import com.vkurylo.shifttracker.widget.WidgetLaunch
import com.vkurylo.shifttracker.data.sync.SyncController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var syncController: SyncController

    /** A widget tap waiting for the navigation graph (see [ShiftTrackerNavHost]). */
    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onStart() {
        super.onStart()
        // Cloud sync, if the user turned it on: at start and on coming back to the app (at most once a minute).
        syncController.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restore) must not replay the tap that opened it.
        if (savedInstanceState == null) consumeLaunch(intent)

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val prefs = preferences ?: return@setContent // DataStore loads in a few ms
            val earningsDisplay by viewModel.earningsDisplay.collectAsStateWithLifecycle()
            val darkTheme = prefs.themeMode.isDark()

            // Keep status/navigation bar icons readable when the app theme differs from the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme },
                )
                onDispose {}
            }

            ShiftTrackerTheme(darkTheme = darkTheme, dynamicColor = prefs.useDynamicColor) {
                CompositionLocalProvider(LocalEarningsDisplay provides earningsDisplay) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        val launch by launchRequests.collectAsStateWithLifecycle()
                        ShiftTrackerNavHost(launchRequest = launch, onLaunchHandled = { launchRequests.value = null })
                    }
                }
                // A crash/unexpected close last time? Show it so it can be copied and sent.
                var crashReport by remember { mutableStateOf(CrashReporter.consume(this@MainActivity)) }
                crashReport?.let { report -> CrashReportDialog(report, onDismiss = { crashReport = null }) }
            }
        }
    }

    // singleTop: a widget tap while the app is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeLaunch(intent)
    }

    /**
     * Takes a widget request out of [intent] and clears it from the activity, so neither a recreation
     * nor a relaunch from Recents opens the same screen again (those are also ignored by [WidgetLaunch.parse]).
     */
    private fun consumeLaunch(intent: Intent?) {
        if (intent?.action != WidgetLaunch.ACTION) return
        WidgetLaunch.parse(intent)?.let { launchRequests.value = it }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    override fun finish() {
        // Leaving the app is only expected from the home screen; anything else is a bug worth reporting.
        // A recreation for a configuration change is not a close (defensive: it normally doesn't call finish()).
        if (!isChangingConfigurations && CrashReporter.currentScreen != "home") CrashReporter.recordUnexpectedFinish(this)
        super.finish()
    }

    private companion object {
        val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
