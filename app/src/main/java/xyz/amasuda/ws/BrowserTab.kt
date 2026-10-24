package xyz.amasuda.ws

import android.webkit.WebView

/** A tab. The WebView is created lazily, so restored sessions only load the tab you look at. */
class BrowserTab(var url: String = "", var title: String = "") {
    var webView: WebView? = null

    /** A tab with nothing loaded shows the home screen. */
    val isHome: Boolean get() = webView == null && url.isBlank()

    fun label(): String = title.ifBlank { url.ifBlank { "ホーム" } }

    fun state() = TabState(url, title)
}
