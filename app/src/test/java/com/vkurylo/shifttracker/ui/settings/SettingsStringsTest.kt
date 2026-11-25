package com.vkurylo.shifttracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ukrainian (values/), English (values-en/) and Polish (values-pl/) say the same things: every string, plural and array
 * is in all three, with the same placeholders, and Polish plurals have all four Polish forms. And Settings describes the data as it is now: exports, imports and the cloud hold bonuses and received
 * payments too, and safety backups are also saved before the current rate is applied to many shifts.
 */
class SettingsStringsTest {
    private class Resources(file: File) {
        private val text = file.readText()
        val strings: Map<String, String> = Regex("""<string name="([^"]+)"(?![^>]*translatable="false")[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals: Map<String, String> = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val arrays: Map<String, Int> = Regex("""<string-array name="([^"]+)"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to Regex("<item>").findAll(it.groupValues[2]).count() }
    }

    private val uk = Resources(File("src/main/res/values/strings.xml"))
    private val en = Resources(File("src/main/res/values-en/strings.xml"))
    private val pl = Resources(File("src/main/res/values-pl/strings.xml"))

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
    fun polishHasEveryStringPluralAndArrayWithTheSamePlaceholders() {
        assertEquals("strings missing in Polish, or only in Polish", emptySet<String>(), uk.strings.keys xor pl.strings.keys)
        assertEquals("plurals missing in Polish, or only in Polish", emptySet<String>(), uk.plurals.keys xor pl.plurals.keys)
        val mismatched = (uk.strings.keys intersect pl.strings.keys).filter { placeholders(uk.strings.getValue(it)) != placeholders(pl.strings.getValue(it)) } +
            (uk.plurals.keys intersect pl.plurals.keys).filter { placeholders(uk.plurals.getValue(it)) != placeholders(pl.plurals.getValue(it)) }
        assertEquals("different placeholders in Polish", emptyList<String>(), mismatched)
        assertEquals("arrays (name to number of items)", uk.arrays, pl.arrays)
        assertEquals("arrays (name to number of items)", uk.arrays, en.arrays)
    }

    @Test
    fun polishPluralsHaveAllFourPolishForms() {
        for ((name, body) in pl.plurals) {
            val forms = Regex("""quantity="(\w+)"""").findAll(body).map { it.groupValues[1] }.toSet()
            assertEquals(name, setOf("one", "few", "many", "other"), forms)
        }
    }

    @Test
    fun thePolishTextSaysTyLikeTheUkrainian() {
        val formal = Regex("""(?U)\b(Pan|Pani|Państwo|Państwa|proszę|Proszę)\b""")
        assertEquals(emptyList<String>(), pl.strings.filterValues { formal.containsMatchIn(it) }.keys.toList())
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
            val polish = pl.strings.getValue(key).lowercase()
            assertTrue("$key (pl): $polish", "premi" in polish && "wypłat" in polish)
        }
    }

    @Test
    fun safetyBackupsMentionTheRateChangeToo() {
        assertTrue(en.strings.getValue("safety_backups_none").contains("rate"))
        assertTrue(uk.strings.getValue("safety_backups_none").contains("ставк"))
        assertTrue(pl.strings.getValue("safety_backups_none").contains("stawk"))
    }

    @Test
    fun theUkrainianTextSaysTiLikeTheRestOfTheApp() {
        val formal = Regex("""(?U)\b(Оновіть|спробуйте|Спробуйте|Перевірте|Торкніться)\b""")
        assertEquals(emptyList<String>(), uk.strings.filterValues { formal.containsMatchIn(it) }.keys.toList())
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> = (this - other) + (other - this)
}
