package com.vkurylo.shifttracker.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Long explanations live behind an ⓘ next to their heading or field: hidden until tapped, then shown in a dialog
 * titled like the heading, closed with OK.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class InfoHintTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val explanation = "What actually arrived, next to the calculated pay after deductions."

    @Test
    fun aHeadingWithInfoShowsTheTextOnlyInItsDialog() {
        composeRule.setContent {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) { SectionHeader("Received pay", info = explanation) }
        }
        composeRule.onNodeWithText("Received pay").assertIsDisplayed()
        composeRule.onNodeWithText(explanation).assertDoesNotExist()

        val button = hasContentDescription(context.getString(R.string.more_info, "Received pay")) and hasClickAction()
        composeRule.onNode(button).performClick()
        composeRule.onNodeWithTag(INFO_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(explanation).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()
        composeRule.onNodeWithTag(INFO_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun aHeadingWithoutInfoHasNoButton() {
        composeRule.setContent {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) { SectionHeader("By job") }
        }
        composeRule.onNode(hasContentDescription(context.getString(R.string.more_info, "By job"))).assertDoesNotExist()
    }
}
