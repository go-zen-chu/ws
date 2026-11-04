package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarAndPageFixesTest {
    @Test
    fun sidebarWidthStaysWithinBounds() {
        assertEquals(184, SidebarWidth.clamp(184, 1920, compact = false))
        assertEquals(SidebarWidth.MIN_DP, SidebarWidth.clamp(10, 1920, compact = false))
        assertEquals(SidebarWidth.MAX_DP, SidebarWidth.clamp(2000, 1920, compact = false))
    }

    @Test
    fun sidebarLeavesRoomForThePage() {
        assertEquals(480, SidebarWidth.clamp(1000, 800, compact = false))
        assertEquals(306, SidebarWidth.clamp(1000, 360, compact = true))
        // A tiny window still gets the minimum.
        assertEquals(SidebarWidth.MIN_DP, SidebarWidth.clamp(300, 100, compact = true))
    }

    @Test
    fun githubGetsTheCodeViewFix() {
        val script = PageFixes.scriptFor("https://github.com/go-zen-chu/ws/blob/main/README.md")
        assertNotNull(script)
        assertTrue(script!!.contains("read-only-cursor-text-area"))
        // The CSS is embedded in a single-quoted JS string.
        assertFalse(PageFixes.cssFor("https://github.com/")!!.contains('\''))
    }

    @Test
    fun pagesCanOnlyOpenWebLinksInNewTabs() {
        assertTrue(LinkClicks.isOpenable("https://github.com/go-zen-chu/ws"))
        assertTrue(LinkClicks.isOpenable("HTTP://localhost:3000/"))
        assertFalse(LinkClicks.isOpenable("javascript:alert(1)"))
        assertFalse(LinkClicks.isOpenable("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(LinkClicks.isOpenable("file:///sdcard/a.html"))
        assertFalse(LinkClicks.isOpenable(null))
        assertTrue(LinkClicks.SCRIPT.contains("window.${LinkClicks.INTERFACE}.open"))
    }

    @Test
    fun otherSitesAreLeftAlone() {
        assertNull(PageFixes.scriptFor("https://github.dev/go-zen-chu/ws"))
        assertNull(PageFixes.scriptFor("https://claude.ai/"))
        assertNull(PageFixes.scriptFor("about:blank"))
        assertNull(PageFixes.scriptFor(null))
        assertNull(PageFixes.scriptFor("not a url"))
    }
}
