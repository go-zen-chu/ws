package xyz.amasuda.ws

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
