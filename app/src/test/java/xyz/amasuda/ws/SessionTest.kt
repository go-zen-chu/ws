package xyz.amasuda.ws

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val tabs = listOf(TabState("https://github.com", "GitHub"), TabState("", ""))

    @Test
    fun roundTripsNamedAndUnnamedSessions() {
        val named = Session("ws", tabs, 1, 42L)
        assertEquals(named, Session.decode(Session.encode(listOf(named))).single())
        val unnamed = named.copy(name = null)
        assertEquals(unnamed, Session.fromJson(unnamed.toJson()))
    }

    @Test
    fun upsertReplacesByNameNewestFirst() {
        val a = Session("a", tabs, 0, 1L)
        val b = Session("b", tabs, 0, 2L)
        val newerA = a.copy(savedAt = 3L, activeIndex = 1)
        assertEquals(listOf(newerA, b), Session.upsert(listOf(b, a), newerA))
    }

    @Test
    fun savesTextZoomWithTheTab() {
        val zoomed = Session("ws", listOf(TabState("https://github.com", "GitHub", 150)), 0, 1L)
        assertEquals(zoomed, Session.fromJson(zoomed.toJson()))
        assertEquals(150, zoomed.toJson().getJSONArray("tabs").getJSONObject(0).getInt("textZoom"))
    }

    @Test
    fun oldSessionsWithoutTextZoomUseTheDefault() {
        val old = Session.fromJson(JSONObject("""{"name":"old","active":0,"savedAt":1,"tabs":[{"url":"u","title":"t"}]}"""))
        assertEquals(TextZoom.DEFAULT, old.tabs.single().textZoom)
        assertFalse(TabState("u", "t").toJson().has("textZoom"))
        // Out-of-range values from a broken save are pulled back into range.
        assertEquals(300, TabState.fromJson(JSONObject().put("url", "u").put("textZoom", 9999)).textZoom)
    }

    @Test
    fun savesPinnedTabsAndTabDisplay() {
        val session = Session("ws", listOf(TabState("https://github.com", "GitHub", pinned = true)), 0, 1L, TabDisplay.ICON)
        assertEquals(session, Session.fromJson(session.toJson()))
        assertFalse(TabState("u", "t").toJson().has("pinned"))
    }

    @Test
    fun savesTabGroups() {
        val groups = listOf(TabGroup("g1", "Review", collapsed = true), TabGroup("g2", "Docs"))
        val session = Session("ws", listOf(TabState("u", "t", group = "g1"), TabState("v", "w")), 0, 1L, groups = groups)
        assertEquals(session, Session.fromJson(session.toJson()))
        assertFalse(TabState("u", "t").toJson().has("group"))
        assertFalse(Session("ws", tabs, 0, 1L).toJson().has("groups"))
    }

    @Test
    fun oldSessionsWithoutTabDisplayUseTheDefault() {
        val old = Session.fromJson(JSONObject("""{"name":"old","active":0,"savedAt":1,"tabs":[{"url":"u","title":"t"}]}"""))
        assertEquals(TabDisplay.DEFAULT, old.tabDisplay)
        assertFalse(old.tabs.single().pinned)
        assertEquals(null, old.tabs.single().group)
        assertEquals(emptyList<TabGroup>(), old.groups)
    }

    private val now = 1_000L * Session.AUTOSAVE_MAX_AGE_MS

    private fun autosave(name: String, unusedFor: Long) = Session(name, tabs, 0, now - unusedFor, autosave = true)

    @Test
    fun ifAnAutosaveIsUnusedForLongerThanTheMaxAgeItShouldBePruned() {
        val old = autosave("old", Session.AUTOSAVE_MAX_AGE_MS + 1)
        val recent = autosave("recent", Session.AUTOSAVE_MAX_AGE_MS - 1)
        assertEquals(listOf(recent), Session.pruneAutosaves(listOf(recent, old), now, open = null))
    }

    @Test
    fun ifASessionWasNamedByTheUserItShouldNeverBePruned() {
        val named = Session("ws", tabs, 0, 0L)
        assertEquals(listOf(named), Session.pruneAutosaves(listOf(named), now, open = null))
    }

    @Test
    fun ifTheOldAutosaveIsTheOpenSessionItShouldBeKept() {
        val old = autosave("old", Session.AUTOSAVE_MAX_AGE_MS * 2)
        assertEquals(listOf(old), Session.pruneAutosaves(listOf(old), now, open = "old"))
    }

    @Test
    fun ifASessionIsAnAutosaveTheFlagShouldBeSavedAndOtherwiseOmitted() {
        val saved = autosave("a", 0L)
        assertEquals(saved, Session.fromJson(saved.toJson()))
        assertTrue(saved.toJson().getBoolean("autosave"))
        assertFalse(Session("ws", tabs, 0, 1L).toJson().has("autosave"))
    }

    @Test
    fun textZoomStepsThroughLevels() {
        assertEquals(110, TextZoom.larger(100))
        assertEquals(90, TextZoom.smaller(100))
        assertEquals(125, TextZoom.larger(113)) // a value between levels snaps to the next one
        assertEquals(300, TextZoom.larger(300))
        assertEquals(50, TextZoom.smaller(50))
    }
}
