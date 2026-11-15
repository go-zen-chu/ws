package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds the code of a user script with the given metadata lines. */
fun userScriptSource(vararg meta: String, name: String = "Test", body: String = "console.log(1);"): String =
    (listOf("// ==UserScript==", "// @name $name") + meta.map { "// @$it" } + "// ==/UserScript==" + body).joinToString("\n")

class UserScriptTest {

    @Test
    fun ifAMetadataBlockIsGivenItShouldBeRead() {
        val meta = UserScriptMeta.parse(
            userScriptSource(
                "namespace https://example.com",
                "version 1.2.3",
                "description Does things",
                "match https://github.com/*",
                "match https://gist.github.com/*",
                "include /^https://example\\.org/",
                "exclude https://github.com/settings*",
                "run-at document-start",
                "grant GM_getValue",
                "grant GM.xmlHttpRequest",
                "require https://cdn.example.com/lib.js",
                "resource css https://cdn.example.com/a.css",
                "connect api.example.com",
                "name:ja テスト",
            ),
        )
        assertEquals("Test", meta.name)
        assertEquals("1.2.3", meta.version)
        assertEquals("Does things", meta.description)
        assertEquals(listOf("https://github.com/*", "https://gist.github.com/*"), meta.matches)
        assertEquals(listOf("/^https://example\\.org/"), meta.includes)
        assertEquals(listOf("https://github.com/settings*"), meta.excludes)
        assertEquals(RunAt.DOCUMENT_START, meta.runAt)
        assertEquals(listOf("GM_getValue", "GM.xmlHttpRequest"), meta.grants)
        assertEquals(listOf("https://cdn.example.com/lib.js"), meta.requires)
        assertEquals(mapOf("css" to "https://cdn.example.com/a.css"), meta.resources)
        assertEquals(listOf("api.example.com"), meta.connects)
    }

    @Test
    fun ifCodeHasNoMetadataBlockOrNameItShouldBeRejected() {
        assertThrows(IllegalArgumentException::class.java) { UserScriptMeta.parse("console.log(1)") }
        assertThrows(IllegalArgumentException::class.java) {
            UserScriptMeta.parse("// ==UserScript==\n// @version 1\n// ==/UserScript==\n")
        }
    }

    @Test
    fun ifRunAtIsMissingOrUnknownItShouldRunWhenThePageHasLoaded() {
        assertEquals(RunAt.DOCUMENT_IDLE, UserScriptMeta.parse(userScriptSource()).runAt)
        assertEquals(RunAt.DOCUMENT_IDLE, RunAt.of("context-menu"))
        assertEquals(RunAt.DOCUMENT_END, RunAt.of("document-body"))
    }

    @Test
    fun ifTheSameNameAndNamespaceAreGivenTheIdShouldBeTheSame() {
        val a = UserScriptMeta.parse(userScriptSource("namespace n", "version 1"))
        val b = UserScriptMeta.parse(userScriptSource("namespace n", "version 2"))
        val c = UserScriptMeta.parse(userScriptSource("namespace other"))
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }

    @Test
    fun ifMatchPatternsAreGivenTheyShouldFollowChromeRules() {
        assertTrue(UrlPatterns.match("https://github.com/*", "https://github.com/go-zen-chu/ws"))
        assertTrue(UrlPatterns.match("https://github.com/*", "https://github.com"))
        assertTrue(UrlPatterns.match("*://*.github.com/*", "http://gist.github.com/x"))
        assertTrue(UrlPatterns.match("*://*.github.com/*", "https://github.com/"))
        assertTrue(UrlPatterns.match("https://github.com/*/pull/*", "https://github.com/a/b/pull/1?w=1"))
        assertTrue(UrlPatterns.match("https://github.com/*", "https://user@github.com:443/x"))
        assertTrue(UrlPatterns.match("<all_urls>", "https://example.com/"))
        assertFalse(UrlPatterns.match("https://github.com/*", "http://github.com/"))
        assertFalse(UrlPatterns.match("*://*.github.com/*", "https://notgithub.com/"))
        assertFalse(UrlPatterns.match("https://github.com/*/pull/*", "https://github.com/a/b/issues/1"))
        assertFalse(UrlPatterns.match("*://*/*", "file:///sdcard/a.html"))
        assertFalse(UrlPatterns.match("not a pattern", "https://github.com/"))
    }

    @Test
    fun ifIncludeGlobsOrRegexesAreGivenTheyShouldMatchTheWholeUrl() {
        assertTrue(UrlPatterns.include("*", "https://example.com/"))
        assertTrue(UrlPatterns.include("https://example.com/*", "https://EXAMPLE.com/a"))
        assertTrue(UrlPatterns.include("http*://example.com/a?b=*", "https://example.com/a?b=1"))
        assertTrue(UrlPatterns.include("/^https://example\\.(com|org)//", "https://example.org/x"))
        assertFalse(UrlPatterns.include("https://example.com/*", "https://example.com.evil/a"))
        assertFalse(UrlPatterns.include("/[unclosed/", "https://example.com/"))
    }

    @Test
    fun ifExcludesCoverAPageTheScriptShouldNotRunThere() {
        val meta = UserScriptMeta.parse(
            userScriptSource("match https://github.com/*", "exclude https://github.com/settings*", "exclude-match https://github.com/login"),
        )
        assertTrue(meta.runsOn("https://github.com/go-zen-chu"))
        assertFalse(meta.runsOn("https://github.com/settings/profile"))
        assertFalse(meta.runsOn("https://github.com/login"))
        assertFalse(meta.runsOn("https://gitlab.com/"))
    }

    @Test
    fun ifNoMatchOrIncludeIsGivenTheScriptShouldRunOnEveryWebPageOnly() {
        val meta = UserScriptMeta.parse(userScriptSource())
        assertTrue(meta.runsOn("https://example.com/"))
        assertFalse(meta.runsOn("about:blank"))
        assertFalse(meta.runsOn("data:text/html,hi"))
    }

    @Test
    fun ifGrantsAreGivenOnlyThoseApisShouldBeAllowed() {
        val none = UserScriptMeta.parse(userScriptSource("grant none"))
        val some = UserScriptMeta.parse(userScriptSource("grant GM.getValue", "grant GM_xmlhttpRequest"))
        val legacy = UserScriptMeta.parse(userScriptSource())
        assertTrue(GmApi.entries.none { none.allows(it) })
        assertEquals(setOf(GmApi.VALUES, GmApi.XHR), GmApi.entries.filter { some.allows(it) }.toSet())
        assertTrue(GmApi.entries.all { legacy.allows(it) })
    }

    @Test
    fun ifARequestTargetsAHostItShouldBeAllowedOnlyByConnectThePageOrTheUser() {
        val script = UserScript(userScriptSource("connect example.com", "connect self"), approvedHosts = setOf("approved.dev"))
        assertTrue(script.mayConnect("page.test", "page.test"))
        assertTrue(script.mayConnect("example.com", "page.test"))
        assertTrue(script.mayConnect("api.example.com", "page.test"))
        assertTrue(script.mayConnect("approved.dev", "page.test"))
        assertFalse(script.mayConnect("notexample.com", "page.test"))
        assertFalse(script.mayConnect("evil.test", "page.test"))
        assertTrue(UserScript(userScriptSource("connect *")).mayConnect("anything.test", "page.test"))
    }

    @Test
    fun ifAScriptIsStoredItShouldComeBackUnchanged() {
        val script = UserScript(
            source = userScriptSource("resource img https://a.test/i.png"),
            requires = listOf("var lib = 1;"),
            resources = mapOf("img" to ScriptResource("https://a.test/i.png", "image/png", "AAEC")),
            sourceUrl = "https://a.test/s.user.js",
            enabled = false,
            approvedHosts = setOf("b.test"),
            installedAt = 1L,
            updatedAt = 2L,
        )
        assertEquals(script, UserScript.fromJson(script.toJson().toString()))
    }

    @Test
    fun ifAStoredFileIsBrokenItShouldBeRejected() {
        assertThrows(IllegalArgumentException::class.java) { UserScript.fromJson("{}") }
        assertThrows(IllegalArgumentException::class.java) { UserScript.fromJson("""{"format":"ws-userscript"}""") }
        assertThrows(IllegalArgumentException::class.java) { UserScript.fromJson("""{"format":"ws-userscript","source":"x"}""") }
    }

    @Test
    fun ifALinkPointsAtAUserJsFileItShouldBeOfferedForInstalling() {
        assertTrue(UserScript.isInstallUrl("https://update.greasyfork.org/scripts/1/Name.user.js"))
        assertTrue(UserScript.isInstallUrl("https://raw.githubusercontent.com/a/b/main/x.user.js?token=1#top"))
        assertFalse(UserScript.isInstallUrl("https://example.com/x.js"))
        assertFalse(UserScript.isInstallUrl("https://example.com/x.user.js.html"))
        assertFalse(UserScript.isInstallUrl("file:///sdcard/x.user.js"))
    }
}
