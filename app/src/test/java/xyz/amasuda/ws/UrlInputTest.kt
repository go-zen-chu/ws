package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlInputTest {
    @Test
    fun keepsExplicitUrls() {
        assertEquals("https://github.com/go-zen-chu/ws", UrlInput.resolve("https://github.com/go-zen-chu/ws"))
        assertEquals("about:blank", UrlInput.resolve("about:blank"))
    }

    @Test
    fun addsHttpsToBareHosts() {
        assertEquals("https://claude.ai", UrlInput.resolve("  claude.ai "))
        assertEquals("https://github.dev/go-zen-chu/ws", UrlInput.resolve("github.dev/go-zen-chu/ws"))
    }

    @Test
    fun usesHttpForLocalDevServers() {
        assertEquals("http://localhost:3000", UrlInput.resolve("localhost:3000"))
        assertEquals("http://127.0.0.1:8080/api", UrlInput.resolve("127.0.0.1:8080/api"))
        assertEquals("http://192.168.0.10:5173", UrlInput.resolve("192.168.0.10:5173"))
    }

    @Test
    fun searchesEverythingElse() {
        assertEquals("https://www.google.com/search?q=kotlin+webview", UrlInput.resolve("kotlin webview"))
        assertTrue(UrlInput.resolve("kotlin")!!.startsWith("https://www.google.com/search?q="))
        assertNull(UrlInput.resolve("   "))
    }

    @Test
    fun desktopUserAgentKeepsChromeVersion() {
        val webView = "Mozilla/5.0 (Linux; Android 14; SM-S921B Build/UP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Safari/537.36",
            UserAgent.desktop(webView),
        )
    }
}
