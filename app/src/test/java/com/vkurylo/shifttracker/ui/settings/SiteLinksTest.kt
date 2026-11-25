package com.vkurylo.shifttracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteLinksTest {
    private val site = "https://demo-project.web.app"

    @Test
    fun `the pages open in the app's language when the site has it, else in English`() {
        assertEquals("$site/privacy?lang=uk", SiteLinks.privacy(site, "uk"))
        assertEquals("$site/privacy?lang=pl", SiteLinks.privacy(site, "pl"))
        assertEquals("$site/delete-account?lang=en", SiteLinks.deleteAccount(site, "en"))
        assertEquals("$site/delete-account?lang=en", SiteLinks.deleteAccount(site, "de"))
    }

    @Test
    fun `a trailing slash on the site doesn't double`() {
        assertEquals("$site/privacy?lang=en", SiteLinks.privacy("$site/", "en"))
    }

    @Test
    fun `no site, no links`() {
        assertNull(SiteLinks.privacy("", "uk"))
        assertNull(SiteLinks.deleteAccount(" ", "uk"))
    }
}
