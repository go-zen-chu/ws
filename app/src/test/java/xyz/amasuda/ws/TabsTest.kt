package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabsTest {
    @Test
    fun insertionRespectsPinnedTabs() {
        assertEquals(2, TabOrder.insertionIndex(1, pinnedCount = 2, size = 5, pinned = false))
        assertEquals(5, TabOrder.insertionIndex(9, pinnedCount = 2, size = 5, pinned = false))
        assertEquals(2, TabOrder.insertionIndex(4, pinnedCount = 2, size = 5, pinned = true))
    }

    @Test
    fun pinningKeepsTheOrderWithinEachGroup() {
        val tabs = listOf("p1" to true, "a" to false, "p2" to true, "b" to false)
        assertEquals(listOf("p1", "p2", "a", "b"), TabOrder.pinnedFirst(tabs) { it.second }.map { it.first })
    }

    @Test
    fun searchMatchesEveryWordInTitleOrUrl() {
        val tabs = listOf(
            TabState("https://github.com/notifications", "Notifications"),
            TabState("https://github.com/pulls", "Pull requests"),
            TabState("", ""),
        )
        assertEquals(listOf(0, 1, 2), TabSearch.filter(tabs, "  "))
        assertEquals(listOf(0, 1), TabSearch.filter(tabs, "GITHUB"))
        assertEquals(listOf(1), TabSearch.filter(tabs, "github pull"))
        assertEquals(emptyList<Int>(), TabSearch.filter(tabs, "gitlab"))
    }

    @Test
    fun faviconKeysAreSafeHostNames() {
        assertEquals("github.com", Favicons.keyFor("https://GitHub.com/pulls?q=1"))
        assertEquals("localhost", Favicons.keyFor("http://localhost:3000/"))
        assertNull(Favicons.keyFor(""))
        assertNull(Favicons.keyFor("about:blank"))
        assertNull(Favicons.keyFor("not a url"))
    }

    @Test
    fun fallbackLetterComesFromTheHostOrTitle() {
        assertEquals("G", Favicons.fallbackLetter("https://www.google.com/", "Google"))
        assertEquals("D", Favicons.fallbackLetter("data:text/html,hi", "doc"))
        assertEquals("?", Favicons.fallbackLetter("", ""))
    }

    @Test
    fun tabDisplayFallsBackToTheDefault() {
        assertEquals(TabDisplay.ICON, TabDisplay.decode("ICON"))
        assertEquals(TabDisplay.DEFAULT, TabDisplay.decode("SOMETHING_ELSE"))
        assertEquals(TabDisplay.DEFAULT, TabDisplay.decode(null))
    }
}
