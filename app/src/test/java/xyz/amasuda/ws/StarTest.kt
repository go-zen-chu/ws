package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarTest {
    private fun star(id: String, url: String) = Star(id, id, url)

    @Test
    fun defaultsStartWithGitHubNotificationsAndPullsWithoutClaudeOrGemini() {
        assertEquals(listOf("github-notifications", "github-pulls"), DEFAULT_STARS.take(2).map { it.id })
        assertTrue(DEFAULT_STARS.none { it.id == "claude" || it.id == "gemini" })
    }

    @Test
    fun ifSavedStarsAreGivenTheyShouldRoundTripThroughJson() {
        val stars = listOf(star("a", "https://a.example"), Star("b", "B", "https://b.example", external = true))
        assertEquals(stars, Star.decode(Star.encode(stars)))
    }

    @Test
    fun ifAPageIsUnderAStarItShouldBeCovered() {
        val github = star("github", "https://github.com/")
        assertTrue(github.covers("https://github.com"))
        assertTrue(github.covers("https://github.com/go-zen-chu/ws"))
        assertTrue(github.covers("https://github.com?tab=repositories"))
        assertFalse(github.covers("https://github.community"))
    }

    @Test
    fun ifStarsAreNestedAPageShouldBelongToTheMostSpecificOne() {
        val pulls = DEFAULT_STARS.first { it.id == "github-pulls" }
        val github = DEFAULT_STARS.first { it.id == "github" }
        assertEquals(pulls, Star.ownerOf("https://github.com/pulls/review-requested", DEFAULT_STARS))
        assertEquals(github, Star.ownerOf("https://github.com/go-zen-chu/ws/pull/5", DEFAULT_STARS))
        assertNull(Star.ownerOf("https://example.com", DEFAULT_STARS))
    }

    @Test
    fun ifOldSitesAreGivenTheyShouldBecomeStarsWithoutClaudeAndGemini() {
        val mine = star("mine", "https://mine.example")
        val oldSites = listOf(
            star("github", "https://github.com"),
            star("claude", "https://claude.ai"),
            Star("gemini", "Gemini", "https://gemini.google.com", external = true),
            mine,
            star("github-dev", "https://github.dev"),
            star("codespaces", "https://github.com/codespaces"),
        )
        val migrated = Star.migrateFromSites(oldSites)
        assertEquals(
            listOf("github-notifications", "github-pulls", "github", "mine", "github-dev", "codespaces"),
            migrated.map { it.id },
        )
    }

    @Test
    fun ifTheUserAlreadyStarredAPageItShouldNotBeAddedTwice() {
        val ownPulls = star("own", "https://github.com/pulls")
        val migrated = Star.migrateFromSites(listOf(ownPulls))
        assertEquals(1, migrated.count { it.url == "https://github.com/pulls" })
    }
}
