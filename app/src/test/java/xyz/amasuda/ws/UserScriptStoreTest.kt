package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** An [HttpFetcher] that answers from a map of URL to body, and 404 for anything else. */
class FakeFetcher(private val bodies: Map<String, String> = emptyMap()) : HttpFetcher {
    val requests = mutableListOf<HttpRequest>()
    var failure: IOException? = null

    override fun fetch(request: HttpRequest, followRedirect: (String) -> Boolean): HttpResponse {
        requests += request
        failure?.let { throw it }
        val body = bodies[request.url] ?: return HttpResponse(404, "Not Found", emptyList(), ByteArray(0), request.url)
        return HttpResponse(200, "OK", listOf("Content-Type" to "text/plain; charset=utf-8"), body.toByteArray(), request.url)
    }
}

class UserScriptStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = UserScriptStore(folder.root)

    private fun script(vararg meta: String, version: String = "1") =
        UserScript(userScriptSource(*meta, "version $version"), sourceUrl = "https://a.test/s.user.js", installedAt = 10L)

    @Test
    fun ifAScriptIsInstalledItShouldBeListedAfterARestart() {
        val installed = store().install(script())
        assertEquals(listOf(installed), store().list())
    }

    @Test
    fun ifAnInstalledScriptIsInstalledAgainItShouldKeepItsSettingsAndValues() {
        val s = store()
        val first = s.install(script())
        s.setEnabled(first.id, false)
        s.approveHost(first.id, "API.test")
        s.setValue(first.id, "k", "1")
        val updated = s.install(script(version = "2").copy(installedAt = 99L))
        assertEquals("2", updated.meta.version)
        assertFalse(updated.enabled)
        assertEquals(setOf("api.test"), updated.approvedHosts)
        assertEquals(10L, updated.installedAt)
        assertEquals("1", store().getValue(first.id, "k"))
    }

    @Test
    fun ifValuesAreSetTheyShouldBeKeptAsJson() {
        val s = store()
        val id = s.install(script()).id
        s.setValue(id, "text", "\"hi\"")
        s.setValue(id, "obj", """{"a":[1,true]}""")
        s.setValue(id, "nothing", "null")
        s.setValue(id, "broken", "{")
        val reloaded = store()
        assertEquals("\"hi\"", reloaded.getValue(id, "text"))
        assertEquals("""{"a":[1,true]}""", reloaded.getValue(id, "obj"))
        assertEquals("null", reloaded.getValue(id, "nothing"))
        assertNull(reloaded.getValue(id, "broken"))
        assertEquals(setOf("text", "obj", "nothing"), reloaded.valueKeys(id).toSet())
        reloaded.deleteValue(id, "text")
        assertNull(store().getValue(id, "text"))
    }

    @Test
    fun ifAScriptIsDeletedItAndItsValuesShouldBeGone() {
        val s = store()
        val id = s.install(script()).id
        s.setValue(id, "k", "1")
        s.delete(id)
        assertTrue(store().list().isEmpty())
        assertNull(store().getValue(id, "k"))
    }

    @Test
    fun ifAPageIsGivenOnlyEnabledScriptsRunningThereShouldBeReturned() {
        val s = store()
        val github = s.install(named("GitHub", "match https://github.com/*"))
        val off = s.install(named("Off", "match https://github.com/*"))
        s.install(named("GitLab", "match https://gitlab.com/*"))
        s.setEnabled(off.id, false)
        assertEquals(listOf(github.id), s.runningOn("https://github.com/a").map { it.id })
    }

    private fun named(name: String, vararg meta: String) = UserScript(userScriptSource(*meta, name = name))
}

class UserScriptInstallerTest {
    private val base = "https://scripts.test/dir/main.user.js"

    @Test
    fun ifAScriptNeedsRequiresAndResourcesTheyShouldBeDownloadedWithIt() {
        val source = userScriptSource("require lib.js", "require https://cdn.test/other.js", "resource txt ../data.txt")
        val fetcher = FakeFetcher(
            mapOf(
                base to source,
                "https://scripts.test/dir/lib.js" to "var lib;",
                "https://cdn.test/other.js" to "var other;",
                "https://scripts.test/data.txt" to "hello",
            ),
        )
        val script = UserScriptInstaller(fetcher) { 5L }.download(base)
        assertEquals(source, script.source)
        assertEquals(listOf("var lib;", "var other;"), script.requires)
        assertEquals(ScriptResource("https://scripts.test/data.txt", "text/plain", "aGVsbG8="), script.resources["txt"])
        assertEquals(base, script.sourceUrl)
        assertEquals(5L, script.installedAt)
        assertTrue(fetcher.requests.none { it.withCookies })
    }

    @Test
    fun ifADownloadFailsTheInstallShouldFail() {
        val fetcher = FakeFetcher(mapOf(base to userScriptSource("require missing.js")))
        assertThrows(IOException::class.java) { UserScriptInstaller(fetcher).download(base) }
        assertThrows(IOException::class.java) { UserScriptInstaller(FakeFetcher()).download(base) }
    }

    @Test
    fun ifTheFileIsNotAUserScriptTheInstallShouldBeRejected() {
        val fetcher = FakeFetcher(mapOf(base to "<html></html>"))
        assertThrows(IllegalArgumentException::class.java) { UserScriptInstaller(fetcher).download(base) }
        assertThrows(IllegalArgumentException::class.java) { UserScriptInstaller(fetcher).download("javascript:alert(1)") }
    }

    @Test
    fun ifAFileWithoutRelativeRequiresIsGivenItShouldInstallWithoutASource() {
        val script = UserScriptInstaller(FakeFetcher()).build(userScriptSource(), "")
        assertEquals("", script.sourceUrl)
    }
}
