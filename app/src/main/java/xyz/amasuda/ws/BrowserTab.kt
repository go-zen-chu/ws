package xyz.amasuda.ws

import android.webkit.WebView

/** A tab. The WebView is created lazily, so restored sessions only load the tab you look at. */
class BrowserTab(
    var url: String = "",
    var title: String = "",
    var textZoom: Int = TextZoom.DEFAULT,
    var pinned: Boolean = false,
) {
    var webView: WebView? = null

    /** A tab with nothing loaded shows the home screen. */
    val isHome: Boolean get() = webView == null && url.isBlank()

    /** Title and URL, for tooltips on tabs that may show only an icon. */
    fun details(home: String): String = listOf(label(home), url).filter { it.isNotBlank() }.distinct().joinToString("\n")

    fun label(home: String): String = title.ifBlank { url.ifBlank { home } }

    fun state() = TabState(url, title, textZoom, pinned)
}

/** Turns a stored tab back into a tab; its page loads when it is first shown. */
fun TabState.toTab() = BrowserTab(url, title, textZoom, pinned)
