package xyz.amasuda.ws

import android.content.Context
import kotlin.math.roundToInt

object Palette {
    val BG = 0xFF1E1E1E.toInt()
    val PANEL = 0xFF252526.toInt()
    val PANEL_DARK = 0xFF181818.toInt()
    val INPUT = 0xFF3C3C3C.toInt()
    val SELECTED = 0xFF37373D.toInt()
    val ACCENT = 0xFF0E639C.toInt()
    val TEXT = 0xFFCCCCCC.toInt()
    val MUTED = 0xFF8B8B8B.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** Width of the resizable sidebar (the vertical tab list), in dp. */
object SidebarWidth {
    const val DEFAULT_DP = 184
    const val MIN_DP = 120
    const val MAX_DP = 560

    /** The fixed width of the icon-only tab list. */
    const val RAIL_DP = 48

    /** The width to show: the icon rail for [TabDisplay.ICON], otherwise the stored width fitted to the window. */
    fun of(display: TabDisplay, storedDp: Int, windowWidthDp: Int, compact: Boolean): Int =
        if (display == TabDisplay.ICON) RAIL_DP else clamp(storedDp, windowWidthDp, compact)

    /** Keeps the sidebar usable while leaving room for the page (a drawer may cover more of a phone). */
    fun clamp(widthDp: Int, windowWidthDp: Int, compact: Boolean): Int {
        val share = if (compact) 0.85 else 0.6
        val max = (windowWidthDp * share).toInt().coerceIn(MIN_DP, MAX_DP)
        return widthDp.coerceIn(MIN_DP, max)
    }
}
