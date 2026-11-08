package xyz.amasuda.ws

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun oldSessionsWithoutTabDisplayUseTheDefault() {
        val old = Session.fromJson(JSONObject("""{"name":"old","active":0,"savedAt":1,"tabs":[{"url":"u","title":"t"}]}"""))
        assertEquals(TabDisplay.DEFAULT, old.tabDisplay)
        assertFalse(old.tabs.single().pinned)
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
