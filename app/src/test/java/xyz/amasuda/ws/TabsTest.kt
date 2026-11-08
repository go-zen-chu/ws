package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabsTest {
    // Tabs 0 and 1 are pinned; centers are 50dp apart.
    private val pinned = listOf(true, true, false, false, false)
    private val centers = listOf(25, 75, 125, 175, 225)

    @Test
    fun draggingMovesToThePositionUnderThePointer() {
        assertEquals(4, TabOrder.dropIndex(centers, pinned, 2, 230))
        assertEquals(3, TabOrder.dropIndex(centers, pinned, 2, 200))
        assertEquals(2, TabOrder.dropIndex(centers, pinned, 2, 130))
        assertEquals(3, TabOrder.dropIndex(centers, pinned, 4, 150))
    }

    @Test
    fun draggedTabsStayInTheirGroup() {
        // An unpinned tab dragged over the pinned ones stops right after them, and the other way round.
        assertEquals(2, TabOrder.dropIndex(centers, pinned, 4, 0))
        assertEquals(1, TabOrder.dropIndex(centers, pinned, 0, 230))
        assertEquals(0, TabOrder.dropIndex(centers, pinned, 1, 10))
    }

    @Test
    fun moveAndInsertionRespectPinnedTabs() {
        assertEquals(listOf("b", "c", "a"), TabOrder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), TabOrder.move(listOf("a", "b", "c"), 2, 0))
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
