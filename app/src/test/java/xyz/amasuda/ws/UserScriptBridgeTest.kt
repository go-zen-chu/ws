package xyz.amasuda.ws

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class UserScriptBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class RecordingHost : UserScriptHost {
        val tabs = mutableListOf<Pair<String, Boolean>>()
        val copied = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val pageJs = mutableListOf<String>()
        var allowConnect: Boolean? = null

        override fun openTab(url: String, background: Boolean) { tabs += url to background }
        override fun copyText(text: String) { copied += text }
        override fun notify(scriptName: String, text: String) { notes += "$scriptName: $text" }
        override fun askToConnect(scriptName: String, host: String, answer: (Boolean) -> Unit) {
            asked += host
            allowConnect?.let(answer)
        }
        override fun runInPage(js: String) { pageJs += js }
    }

    private val inline = Executor { it.run() }
    private val page = "https://page.test/a"
    private val host = RecordingHost()
    private val fetcher = FakeFetcher(mapOf("https://api.test/x" to "pong", "https://page.test/y" to "self"))
    private lateinit var store: UserScriptStore

    /** A bridge on [page] with one installed script; returns the bridge and the token the script got. */
    private fun bridgeWith(vararg meta: String): Pair<UserScriptBridge, String> {
        store = UserScriptStore(folder.root)
        store.install(UserScript(userScriptSource("match https://page.test/*", *meta)))
        var n = 0
        val bridge = UserScriptBridge(store, fetcher, host, inline, inline, "1.0") { "token${++n}" }
        assertEquals(1, bridge.injections(page, RunAt.entries.toSet()).size)
        return bridge to "token1"
    }

    private fun xhrJson(url: String) = JSONObject().put("url", url).toString()

    @Test
    fun ifAScriptHasRunOnTheDocumentItShouldNotBeInjectedAgain() {
        val (bridge, _) = bridgeWith()
        assertTrue(bridge.injections(page, RunAt.entries.toSet()).isEmpty())
        bridge.newPage()
        assertEquals(1, bridge.injections(page, RunAt.entries.toSet()).size)
    }

    @Test
    fun ifThePhaseIsNotTheScriptsRunAtItShouldWait() {
        store = UserScriptStore(folder.root)
        store.install(UserScript(userScriptSource("match https://page.test/*")))
        val bridge = UserScriptBridge(store, fetcher, host, inline, inline, "1.0")
        assertTrue(bridge.injections(page, setOf(RunAt.DOCUMENT_START, RunAt.DOCUMENT_END)).isEmpty())
        assertEquals(1, bridge.injections(page, setOf(RunAt.DOCUMENT_IDLE)).size)
    }

    @Test
    fun ifTheScriptsTokenIsGivenItsValuesShouldBeReadAndWritten() {
        val (bridge, token) = bridgeWith("grant GM_setValue")
        bridge.setValue(token, "k", """{"a":1}""")
        assertEquals("""{"a":1}""", bridge.getValue(token, "k"))
        assertEquals(listOf("k"), JSONArray(bridge.listValues(token)).let { a -> List(a.length()) { a.getString(it) } })
        bridge.deleteValue(token, "k")
        assertNull(bridge.getValue(token, "k"))
    }

    @Test
    fun ifAnUnknownTokenIsGivenNothingShouldBeAllowed() {
        val (bridge, token) = bridgeWith()
        bridge.setValue(token, "k", "1")
        assertNull(bridge.getValue("forged", "k"))
        assertNull(bridge.getValue(null, "k"))
        bridge.openInTab("forged", "https://x.test/", false)
        bridge.xhr("forged", "1", xhrJson("https://api.test/x"))
        assertTrue(host.tabs.isEmpty())
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun ifThePageChangedTheOldTokenShouldStopWorking() {
        val (bridge, token) = bridgeWith()
        bridge.setValue(token, "k", "1")
        bridge.newPage()
        assertNull(bridge.getValue(token, "k"))
    }

    @Test
    fun ifAnApiIsNotGrantedItShouldBeRefused() {
        val (bridge, token) = bridgeWith("grant GM_addStyle")
        bridge.setValue(token, "k", "1")
        bridge.openInTab(token, "https://x.test/", false)
        bridge.setClipboard(token, "secret")
        bridge.xhr(token, "1", xhrJson("https://api.test/x"))
        assertNull(store.getValue(store.list().single().id, "k"))
        assertTrue(host.tabs.isEmpty() && host.copied.isEmpty() && fetcher.requests.isEmpty())
    }

    @Test
    fun ifTheScriptIsDisabledItsCallsShouldBeRefused() {
        val (bridge, token) = bridgeWith()
        store.setEnabled(store.list().single().id, false)
        bridge.setClipboard(token, "x")
        assertTrue(host.copied.isEmpty())
    }

    @Test
    fun ifGrantedTabsClipboardAndNotificationsShouldReachTheApp() {
        val (bridge, token) = bridgeWith("grant GM_openInTab", "grant GM_setClipboard", "grant GM_notification")
        bridge.openInTab(token, "https://x.test/", true)
        bridge.openInTab(token, "javascript:alert(1)", false)
        bridge.setClipboard(token, "copied")
        bridge.notification(token, "hello")
        assertEquals(listOf("https://x.test/" to true), host.tabs)
        assertEquals(listOf("copied"), host.copied)
        assertEquals(listOf("Test: hello"), host.notes)
    }

    @Test
    fun ifAMenuCommandIsRegisteredItShouldBeListedUntilThePageChanges() {
        val (bridge, token) = bridgeWith()
        bridge.registerMenuCommand(token, "m1", "Do it")
        bridge.registerMenuCommand(token, "m1", "Do it again")
        bridge.registerMenuCommand(token, "m2", "Other")
        bridge.unregisterMenuCommand(token, "m2")
        assertEquals(listOf("Do it again"), bridge.menuCommands.map { it.caption })
        bridge.runCommand(bridge.menuCommands.single())
        assertTrue(host.pageJs.single().contains("__wsGMmenu[\"${store.list().single().id}\"]"))
        bridge.newPage()
        assertTrue(bridge.menuCommands.isEmpty())
    }

    @Test
    fun ifARequestGoesToAConnectHostItShouldBeAnsweredWithoutAsking() {
        val (bridge, token) = bridgeWith("connect api.test")
        bridge.xhr(token, "token1.1", xhrJson("https://api.test/x"))
        assertTrue(host.asked.isEmpty())
        val js = host.pageJs.single()
        assertTrue(js.startsWith("window.__wsGMxhr&&window.__wsGMxhr[\"token1.1\"]"))
        assertTrue(js.contains("\"text\":\"pong\""))
    }

    @Test
    fun ifARequestGoesToThePagesOwnHostItShouldBeAllowed() {
        val (bridge, token) = bridgeWith()
        bridge.xhr(token, "1", xhrJson("https://page.test/y"))
        assertTrue(host.asked.isEmpty())
        assertTrue(host.pageJs.single().contains("\"text\":\"self\""))
    }

    @Test
    fun ifARequestGoesToAnotherHostTheUserShouldBeAskedOnce() {
        val (bridge, token) = bridgeWith()
        host.allowConnect = true
        bridge.xhr(token, "1", xhrJson("https://api.test/x"))
        bridge.xhr(token, "2", xhrJson("https://api.test/x"))
        assertEquals(listOf("api.test"), host.asked)
        assertEquals(setOf("api.test"), store.list().single().approvedHosts)
        assertEquals(2, fetcher.requests.size)
    }

    @Test
    fun ifTheUserDeniesAConnectionTheRequestShouldFail() {
        val (bridge, token) = bridgeWith()
        host.allowConnect = false
        bridge.xhr(token, "1", xhrJson("https://api.test/x"))
        assertTrue(fetcher.requests.isEmpty())
        assertTrue(host.pageJs.single().contains("\"error\""))
        assertTrue(store.list().single().approvedHosts.isEmpty())
    }

    @Test
    fun ifAMalformedRequestIsGivenItShouldFailWithoutSending() {
        val (bridge, token) = bridgeWith()
        bridge.xhr(token, "1", """{"url":"file:///etc/hosts"}""")
        bridge.xhr(token, "2", "not json")
        assertTrue(fetcher.requests.isEmpty())
        assertEquals(2, host.pageJs.count { it.contains("\"error\"") })
    }

    @Test
    fun ifAScriptIsInjectedItShouldCarryItsCodeRequiresAndToken() {
        val script = UserScript(userScriptSource("run-at document-end", body = "main();"), requires = listOf("function main(){}"))
        val js = UserScriptRuntime.injection(script, "tok", "1.0")
        assertTrue(js.indexOf("function main(){}") < js.indexOf("main();"))
        // Only as an argument: a global the page can enumerate (menu or request callbacks) would leak it.
        assertEquals(1, Regex("\"tok\"").findAll(js).count())
        assertTrue(js.contains("DOMContentLoaded"))
        assertFalse(UserScriptRuntime.injection(UserScript(userScriptSource()), "tok", "1.0").contains("DOMContentLoaded"))
    }
}

class XhrRequestTest {
    @Test
    fun ifAPageRequestIsGivenItShouldBeReadWithDefaults() {
        val r = XhrRequest.decode("""{"url":"https://a.test/","headers":{"X-A":"1"},"data":"d","anonymous":true,"binary":true}""")
        assertEquals("GET", r.http.method)
        assertEquals(mapOf("X-A" to "1"), r.http.headers)
        assertEquals("d", r.http.body)
        assertFalse(r.http.withCookies)
        assertTrue(r.binary)
        assertEquals(HttpFetcher.DEFAULT_TIMEOUT_MS, r.http.timeoutMs)
        assertEquals("a.test", r.host)
    }

    @Test
    fun ifAMalformedRequestIsGivenItShouldBeRejected() {
        assertThrows(IllegalArgumentException::class.java) { XhrRequest.decode("""{"url":"ftp://a.test/"}""") }
        assertThrows(IllegalArgumentException::class.java) { XhrRequest.decode("""{"url":"https://a.test/","method":"CONNECT"}""") }
        assertThrows(IllegalArgumentException::class.java) { XhrRequest.decode("""{}""") }
        assertThrows(IllegalArgumentException::class.java) { XhrRequest.decode(null) }
    }

    @Test
    fun ifHeadersContainLineBreaksTheyShouldBeDropped() {
        val r = XhrRequest.decode("""{"url":"https://a.test/","headers":{"A":"1\r\nB: 2","C":"3"}}""")
        assertEquals(mapOf("C" to "3"), r.http.headers)
    }

    @Test
    fun ifAResponseIsEncodedTextAndBinaryShouldTravelAsTheScriptAsked() {
        val response = HttpResponse(200, "OK", listOf("Content-Type" to "text/plain; charset=ISO-8859-1"), byteArrayOf(0xE9.toByte()), "https://a.test/")
        val text = JSONObject(XhrRequest.decode("""{"url":"https://a.test/"}""").encodeResponse(response))
        assertEquals("é", text.getString("text"))
        assertTrue(text.getString("headers").startsWith("content-type: text/plain"))
        val binary = JSONObject(XhrRequest.decode("""{"url":"https://a.test/","binary":true}""").encodeResponse(response))
        assertEquals("6Q==", binary.getString("base64"))
        assertFalse(binary.has("text"))
    }
}
