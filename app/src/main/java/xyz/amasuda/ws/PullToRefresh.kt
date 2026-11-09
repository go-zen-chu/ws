package xyz.amasuda.ws

import kotlin.math.abs

/**
 * Pull down at the top of a page to reload it, like Chrome on Android.
 *
 * The WebView only reports the document's own scroll position, so a page that scrolls an inner element
 * (github.dev's editor, file trees) would look "at the top" all the time. The injected script tells the app
 * at each touchstart whether the page is really at the top, and when the page handles the drag itself
 * (preventDefault on touchmove), so those pulls never reload.
 */
object PullToRefresh {
    const val INTERFACE = "wsPull"

    val SCRIPT = "(function(){if(window.__wsPull)return;window.__wsPull=1;var blocked=0;" +
        "function stops(el){var s=el&&getComputedStyle(el).overscrollBehaviorY;return s==='none'||s==='contain';}" +
        "function atTop(e){if(e.touches.length!==1||window.scrollY>0)return false;" +
        "if(stops(document.documentElement)||stops(document.body))return false;" +
        "for(var n=e.target;n&&n.nodeType===1;n=n.parentElement){if(n.scrollTop>0)return false;}return true;}" +
        "document.addEventListener('touchstart',function(e){blocked=0;window.$INTERFACE.start(atTop(e));}," +
        "{capture:true,passive:true});" +
        "window.addEventListener('touchmove',function(e){if(!blocked&&e.defaultPrevented){blocked=1;" +
        "window.$INTERFACE.block();}},{passive:true});})()"
}

/**
 * Tracks one finger drag and decides whether it is a pull to refresh. Distances are in pixels;
 * the finger moves twice the pull distance, which makes the pull feel resistant.
 */
class PullGesture(private val triggerPx: Float, private val slopPx: Float) {
    private enum class State { IDLE, WAITING, PULLING, IGNORED }

    private var state = State.IDLE
    private var startX = 0f
    private var startY = 0f

    /** Null until the page reports whether this touch started at its top. */
    private var pageAtTop: Boolean? = null

    /** How far the pull has gone: 0 when not pulling, 1 at the point where releasing reloads. */
    var progress = 0f
        private set

    val pulling: Boolean get() = state == State.PULLING

    /** A finger touched the page; [documentAtTop] is the WebView's own scroll check. */
    fun down(x: Float, y: Float, documentAtTop: Boolean) {
        state = if (documentAtTop) State.WAITING else State.IGNORED
        startX = x
        startY = y
        pageAtTop = null
        progress = 0f
    }

    /** The page's answer for the current touch. */
    fun pageReported(atTop: Boolean) {
        pageAtTop = atTop
        if (!atTop) ignore()
    }

    /** The page scrolls or handles this drag itself. */
    fun block() = ignore()

    /** A second finger or a cancelled touch ends the gesture without a reload. */
    fun cancel() = ignore()

    fun move(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        when (state) {
            State.WAITING -> {
                if (abs(dx) < slopPx && abs(dy) < slopPx) return
                if (dy <= abs(dx)) {
                    ignore()
                    return
                }
                // The page's answer comes asynchronously; keep waiting for it rather than guessing.
                if (pageAtTop != true) return
                state = State.PULLING
                startY += slopPx
                progress = pullProgress(y)
            }
            State.PULLING -> progress = pullProgress(y)
            else -> Unit
        }
    }

    /** The finger lifted: true when the page should reload. */
    fun up(): Boolean {
        val reload = state == State.PULLING && progress >= 1f
        ignore()
        state = State.IDLE
        return reload
    }

    private fun pullProgress(y: Float): Float = ((y - startY) * DRAG_RATE / triggerPx).coerceAtLeast(0f)

    private fun ignore() {
        if (state != State.IDLE) state = State.IGNORED
        progress = 0f
    }

    private companion object {
        const val DRAG_RATE = 0.5f
    }
}
