package xyz.amasuda.ws

import java.net.URI
import java.net.URLEncoder

const val PREFS_NAME = "ws"
const val TERMUX_PACKAGE = "com.termux"
const val TERMUX_FDROID_URL = "https://f-droid.org/packages/com.termux/"

object UrlInput {
    private const val SEARCH_URL = "https://www.google.com/search?q="
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val LOCAL = Regex("^(localhost|127(\\.\\d{1,3}){3}|10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|\\[::1])(:\\d+)?(/.*)?$")
    private val HOST = Regex("^[^\\s/]+\\.[^\\s/]+(:\\d+)?(/.*)?$")

    /** Turns address-bar input into a URL: explicit URLs, bare hosts, local dev servers or a search. */
    fun resolve(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (LOCAL.matches(s)) return "http://$s"
        if (HOST.matches(s) && !s.contains("://")) return "https://$s"
        if (SCHEME.containsMatchIn(s) && !s.contains(' ')) return s
        return SEARCH_URL + URLEncoder.encode(s, "UTF-8")
    }
}

object UserAgent {
    private val CHROME = Regex("Chrome/[\\d.]+")

    /** A desktop Chrome UA built from the WebView's own Chrome version. */
    fun desktop(webViewDefault: String): String {
        val chrome = CHROME.find(webViewDefault)?.value ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
}

/** Small CSS fixes injected into sites that do not work well in a WebView. */
object PageFixes {
    private const val STYLE_ID = "ws-page-fixes"

    // GitHub's code view lays a transparent read-only <textarea> over the code. In a WebView a drag on it
    // starts a text selection instead of scrolling, so let touches through to the code lines underneath,
    // which scroll with a drag and can still be selected (long-press, or a mouse drag on DeX).
    private const val GITHUB_CSS =
        "#read-only-cursor-text-area{pointer-events:none!important}" +
            ".react-code-lines,.react-code-lines *{-webkit-user-select:text!important;user-select:text!important}"

    fun cssFor(url: String?): String? {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase() ?: return null
        return when (host) {
            "github.com", "www.github.com" -> GITHUB_CSS
            else -> null
        }
    }

    /** JavaScript that adds the fixes once per document (it is run again after every SPA navigation). */
    fun scriptFor(url: String?): String? {
        val css = cssFor(url) ?: return null
        return "(function(){if(document.getElementById('$STYLE_ID'))return;" +
            "var s=document.createElement('style');s.id='$STYLE_ID';s.textContent='$css';" +
            "(document.head||document.documentElement).appendChild(s);})()"
    }
}
