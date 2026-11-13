package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHintsTest {
    private fun assertPrefixFree(labels: List<String>) {
        labels.forEach { a ->
            assertFalse("$a is a prefix of another label", labels.any { b -> b != a && b.startsWith(a) })
        }
    }

    @Test
    fun ifFewTargetsAreGivenEachShouldGetOneCharacter() {
        val labels = LinkHints.labels(5)
        assertEquals(5, labels.size)
        assertTrue(labels.all { it.length == 1 })
    }

    @Test
    fun ifManyTargetsAreGivenLabelsShouldBeUniqueAndPrefixFree() {
        listOf(LinkHints.ALPHABET.length, LinkHints.ALPHABET.length + 1, 150, 500).forEach { count ->
            val labels = LinkHints.labels(count)
            assertEquals(count, labels.size)
            assertEquals(count, labels.toSet().size)
            assertPrefixFree(labels)
            assertTrue(labels.all { label -> label.all { it in LinkHints.ALPHABET } })
        }
    }

    @Test
    fun ifTargetsJustExceedTheAlphabetMostLabelsShouldStayShort() {
        val labels = LinkHints.labels(LinkHints.ALPHABET.length + 1)
        assertEquals(2, labels.maxOf { it.length })
        assertTrue(labels.count { it.length == 1 } >= LinkHints.ALPHABET.length - 1)
    }

    @Test
    fun ifNoOrTooManyTargetsAreGivenItShouldStayWithinBounds() {
        assertTrue(LinkHints.labels(0).isEmpty())
        assertTrue(LinkHints.labels(-3).isEmpty())
        assertEquals(LinkHints.MAX_HINTS, LinkHints.labels(LinkHints.MAX_HINTS + 100).size)
    }

    @Test
    fun ifThePageReportsAModeItShouldBeParsedAndAnythingElseShouldCountAsVisual() {
        assertEquals(InputMode.INSERT, InputMode.of("insert"))
        assertEquals(InputMode.VISUAL, InputMode.of("visual"))
        assertEquals(InputMode.VISUAL, InputMode.of("hacked"))
        assertEquals(InputMode.VISUAL, InputMode.of(null))
    }
}
