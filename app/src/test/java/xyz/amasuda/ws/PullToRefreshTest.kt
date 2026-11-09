package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullToRefreshTest {
    private val trigger = 64f
    private val slop = 8f

    /** Starts a touch at the top of a page that reported it is at the top. */
    private fun touchAtTop(): PullGesture = PullGesture(trigger, slop).apply {
        down(100f, 0f, documentAtTop = true)
        pageReported(true)
    }

    /** The finger distance that pulls exactly to the trigger point. */
    private val fullPull = slop + trigger * 2

    @Test
    fun ifAPullAtTheTopPassesTheTriggerItShouldReload() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull)
        assertTrue(gesture.pulling)
        assertEquals(1f, gesture.progress, 0.001f)
        assertTrue(gesture.up())
        assertFalse(gesture.pulling)
    }

    @Test
    fun ifAPullIsReleasedBeforeTheTriggerItShouldNotReload() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull / 2)
        assertTrue(gesture.pulling)
        assertFalse(gesture.up())
    }

    @Test
    fun ifThePullGoesBackUpItShouldNotReload() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull)
        gesture.move(100f, slop)
        assertEquals(0f, gesture.progress, 0.001f)
        assertFalse(gesture.up())
    }

    @Test
    fun ifTheDocumentIsScrolledItShouldNotPull() {
        val gesture = PullGesture(trigger, slop).apply {
            down(100f, 0f, documentAtTop = false)
            pageReported(true)
        }
        gesture.move(100f, fullPull)
        assertFalse(gesture.pulling)
        assertFalse(gesture.up())
    }

    @Test
    fun ifThePageSaysAnInnerElementIsScrolledItShouldNotPull() {
        val gesture = PullGesture(trigger, slop).apply {
            down(100f, 0f, documentAtTop = true)
            pageReported(false)
        }
        gesture.move(100f, fullPull)
        assertFalse(gesture.pulling)
        assertFalse(gesture.up())
    }

    @Test
    fun ifThePageAnswersLateItShouldStartPullingOnceItAnswers() {
        val gesture = PullGesture(trigger, slop).apply { down(100f, 0f, documentAtTop = true) }
        gesture.move(100f, fullPull / 2)
        assertFalse(gesture.pulling)
        gesture.pageReported(true)
        gesture.move(100f, fullPull)
        assertTrue(gesture.pulling)
        assertTrue(gesture.up())
    }

    @Test
    fun ifThePageNeverAnswersItShouldNotReload() {
        val gesture = PullGesture(trigger, slop).apply { down(100f, 0f, documentAtTop = true) }
        gesture.move(100f, fullPull * 2)
        assertFalse(gesture.up())
    }

    @Test
    fun ifThePageHandlesTheDragItselfItShouldNotReload() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull)
        gesture.block()
        assertFalse(gesture.pulling)
        gesture.move(100f, fullPull * 2)
        assertFalse(gesture.up())
    }

    @Test
    fun ifTheDragIsSidewaysOrUpItShouldNotPull() {
        val sideways = touchAtTop()
        sideways.move(100f + fullPull, fullPull / 2)
        sideways.move(100f, fullPull)
        assertFalse(sideways.up())

        val up = touchAtTop()
        up.move(100f, -slop * 2)
        up.move(100f, fullPull)
        assertFalse(up.up())
    }

    @Test
    fun ifTheTouchIsCancelledItShouldNotReload() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull)
        gesture.cancel()
        assertFalse(gesture.up())
    }

    @Test
    fun ifANewTouchStartsItShouldForgetThePreviousOne() {
        val gesture = touchAtTop()
        gesture.move(100f, fullPull)
        gesture.cancel()
        gesture.down(100f, 0f, documentAtTop = true)
        gesture.pageReported(true)
        gesture.move(100f, fullPull)
        assertTrue(gesture.up())
    }

    @Test
    fun theScriptReportsToTheAppInterface() {
        assertTrue(PullToRefresh.SCRIPT.contains("window.${PullToRefresh.INTERFACE}.start("))
        assertTrue(PullToRefresh.SCRIPT.contains("window.${PullToRefresh.INTERFACE}.block()"))
    }
}
