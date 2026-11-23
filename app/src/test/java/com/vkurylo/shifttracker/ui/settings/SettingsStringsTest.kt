package com.vkurylo.shifttracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ukrainian (values/) and English (values-en/) say the same things: every string and plural is in both, with the same
 * placeholders. And Settings describes the data as it is now: exports, imports and the cloud hold bonuses and received
 * payments too, and safety backups are also saved before the current rate is applied to many shifts.
 */
class SettingsStringsTest {
    private class Resources(file: File) {
        private val text = file.readText()
        val strings: Map<String, String> = Regex("""<string name="([^"]+)"(?![^>]*translatable="false")[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals: Map<String, String> = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val uk = Resources(File("src/main/res/values/strings.xml"))
    private val en = Resources(File("src/main/res/values-en/strings.xml"))

    private fun placeholders(text: String): Set<String> = Regex("""%(\d+\$)?[sdf]""").findAll(text).map { it.value }.toSet()

    @Test
    fun everyStringAndPluralIsInBothLanguagesWithTheSamePlaceholders() {
        assertEquals("strings only in one language", emptySet<String>(), uk.strings.keys xor en.strings.keys)
        assertEquals("plurals only in one language", emptySet<String>(), uk.plurals.keys xor en.plurals.keys)
        val mismatched = (uk.strings.keys intersect en.strings.keys).filter { placeholders(uk.strings.getValue(it)) != placeholders(en.strings.getValue(it)) } +
            (uk.plurals.keys intersect en.plurals.keys).filter { placeholders(uk.plurals.getValue(it)) != placeholders(en.plurals.getValue(it)) }
        assertEquals("different placeholders", emptyList<String>(), mismatched)
    }

    @Test
    fun dataStringsNameBonusesAndReceivedPayments() {
        val keys = listOf(
            "export_data_summary",
            "import_backup_message",
            "import_backup_message_linked",
            "import_backup_message_synced",
            "cloud_delete_message",
            "cloud_delete_summary",
        )
        for (key in keys) {
            val english = en.strings.getValue(key).lowercase()
            val ukrainian = uk.strings.getValue(key).lowercase()
            assertTrue("$key (en): $english", "bonus" in english && "received" in english)
            assertTrue("$key (uk): $ukrainian", "бонус" in ukrainian && "виплат" in ukrainian)
        }
    }

    @Test
    fun safetyBackupsMentionTheRateChangeToo() {
        assertTrue(en.strings.getValue("safety_backups_none").contains("rate"))
        assertTrue(uk.strings.getValue("safety_backups_none").contains("ставк"))
    }

    @Test
    fun theUkrainianTextSaysTiLikeTheRestOfTheApp() {
        val formal = Regex("""(?U)\b(Оновіть|спробуйте|Спробуйте|Перевірте|Торкніться)\b""")
        assertEquals(emptyList<String>(), uk.strings.filterValues { formal.containsMatchIn(it) }.keys.toList())
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> = (this - other) + (other - this)
}
