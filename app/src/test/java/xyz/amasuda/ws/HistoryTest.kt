package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun entries(count: Int) = (0 until count).map { HistoryEntry("Page $it", "https://example.com/$it") }

    @Test
    fun ifPagesAreBehindTheCurrentOneBackShouldListThemNearestFirst() {
        val jumps = History.back(entries(4), current = 2)
        assertEquals(listOf(-1, -2), jumps.map { it.steps })
        assertEquals(listOf("Page 1", "Page 0"), jumps.map { it.label })
    }

    @Test
    fun ifPagesAreAheadOfTheCurrentOneForwardShouldListThemNearestFirst() {
        val jumps = History.forward(entries(5), current = 1)
        assertEquals(listOf(1, 2, 3), jumps.map { it.steps })
        assertEquals("https://example.com/4", jumps.last().url)
    }

    @Test
    fun ifTheCurrentPageIsAtAnEndThatDirectionShouldBeEmpty() {
        assertTrue(History.back(entries(3), current = 0).isEmpty())
        assertTrue(History.forward(entries(3), current = 2).isEmpty())
    }

    @Test
    fun ifTheHistoryIsLongTheListShouldStopAtTheLimit() {
        val jumps = History.back(entries(30), current = 29, limit = 5)
        assertEquals(listOf(-1, -2, -3, -4, -5), jumps.map { it.steps })
    }

    @Test
    fun ifAPageHasNoTitleItShouldBeLabelledWithItsUrl() {
        val jumps = History.back(listOf(HistoryEntry(" ", "https://a.example"), HistoryEntry("B", "https://b.example")), current = 1)
        assertEquals("https://a.example", jumps.single().label)
    }

    @Test
    fun ifTheCurrentIndexIsOutOfRangeItShouldListNothing() {
        assertTrue(History.back(entries(3), current = -1).isEmpty())
        assertTrue(History.forward(entries(3), current = 3).isEmpty())
    }
}
