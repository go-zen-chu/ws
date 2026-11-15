package xyz.amasuda.ws

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64

/** The installed scripts and their stored values, as the page bridge sees them. */
interface UserScriptRepository {
    fun find(id: String): UserScript?

    /** The enabled scripts that run on [url]. */
    fun runningOn(url: String): List<UserScript>

    /** Lets the script's `GM_xmlhttpRequest` reach [host] from now on. */
    fun approveHost(id: String, host: String)

    /** A stored value as JSON, or null when there is none. */
    fun getValue(id: String, key: String): String?
    fun setValue(id: String, key: String, json: String)
    fun deleteValue(id: String, key: String)
    fun valueKeys(id: String): List<String>
}

/**
 * Keeps each script as `<id>.json` in [dir] and its `GM_setValue` values as `<id>.values.json`.
 * Scripts are files rather than SharedPreferences entries because they and their `@require`s can be large.
 * Safe to use from the page bridge's threads.
 */
class UserScriptStore(private val dir: File) : UserScriptRepository {
    private val lock = Any()
    private var cache: MutableMap<String, UserScript>? = null
    private val values = HashMap<String, JSONObject>()

    /** All installed scripts, by name. */
    fun list(): List<UserScript> = synchronized(lock) { scripts().values.sortedBy { it.meta.name.lowercase() } }

    override fun find(id: String): UserScript? = synchronized(lock) { scripts()[id] }

    override fun runningOn(url: String): List<UserScript> = list().filter { it.enabled && it.meta.runsOn(url) }

    /**
     * Installs [script], or updates the installed script with the same name and namespace, keeping whether
     * it is enabled, the hosts the user approved and its values. Returns what was stored.
     */
    fun install(script: UserScript): UserScript = synchronized(lock) {
        val old = scripts()[script.id]
        val merged = if (old == null) script else script.copy(
            enabled = old.enabled,
            approvedHosts = old.approvedHosts,
            installedAt = old.installedAt,
            sourceUrl = script.sourceUrl.ifBlank { old.sourceUrl },
        )
        write(merged)
        merged
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    override fun approveHost(id: String, host: String) = update(id) { it.copy(approvedHosts = it.approvedHosts + host.lowercase()) }

    fun delete(id: String) = synchronized(lock) {
        scripts().remove(id)
        values.remove(id)
        File(dir, "$id.json").delete()
        File(dir, "$id.values.json").delete()
        Unit
    }

    override fun getValue(id: String, key: String): String? = synchronized(lock) {
        valuesOf(id).opt(key)?.let { v -> JSONArray().put(v).toString().let { it.substring(1, it.length - 1) } }
    }

    override fun setValue(id: String, key: String, json: String) = synchronized(lock) {
        val parsed = runCatching { JSONArray("[$json]").get(0) }.getOrElse { return@synchronized }
        writeValues(id, valuesOf(id).put(key, parsed))
    }

    override fun deleteValue(id: String, key: String) = synchronized(lock) {
        val v = valuesOf(id)
        if (v.has(key)) {
            v.remove(key)
            writeValues(id, v)
        }
    }

    override fun valueKeys(id: String): List<String> = synchronized(lock) { valuesOf(id).keys().asSequence().toList() }

    private fun update(id: String, change: (UserScript) -> UserScript) = synchronized(lock) {
        scripts()[id]?.let { write(change(it)) }
        Unit
    }

    private fun scripts(): MutableMap<String, UserScript> = cache ?: (dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") && !it.name.endsWith(".values.json") }
        .mapNotNull { f -> runCatching { UserScript.fromJson(f.readText()) }.getOrNull() }
        .associateBy { it.id }.toMutableMap()).also { cache = it }

    private fun write(script: UserScript) {
        writeAtomically(File(dir, "${script.id}.json"), script.toJson().toString())
        scripts()[script.id] = script
    }

    private fun valuesOf(id: String): JSONObject = values.getOrPut(id) {
        File(dir, "$id.values.json").takeIf { it.exists() }?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull() } ?: JSONObject()
    }

    private fun writeValues(id: String, v: JSONObject) {
        values[id] = v
        writeAtomically(File(dir, "$id.values.json"), v.toString())
    }

    private fun writeAtomically(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("cannot write $file")
    }
}

/** Downloads a script with its `@require`s and `@resource`s, so it runs without the network afterwards. */
class UserScriptInstaller(
    private val fetcher: HttpFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Throws [IOException] when a download fails and [IllegalArgumentException] when it is not a user script. */
    fun download(url: String): UserScript = build(fetch(url).text(), url)

    /** Completes a script given as code (e.g. a file); relative `@require`s resolve against [sourceUrl]. */
    fun build(source: String, sourceUrl: String): UserScript {
        val meta = UserScriptMeta.parse(source)
        val requires = meta.requires.map { fetch(resolve(sourceUrl, it)).text() }
        val resources = meta.resources.mapValues { (_, ref) ->
            val url = resolve(sourceUrl, ref)
            val r = fetch(url)
            ScriptResource(url, r.mimeType, Base64.getEncoder().encodeToString(r.body))
        }
        val now = clock()
        return UserScript(source, requires, resources, sourceUrl, installedAt = now, updatedAt = now)
    }

    private fun fetch(url: String): HttpResponse {
        require(UrlPatterns.isWeb(url)) { "not a web URL: $url" }
        val r = fetcher.fetch(HttpRequest("GET", url, withCookies = false))
        if (r.status !in 200..299) throw IOException("HTTP ${r.status} for $url")
        return r
    }

    private fun resolve(base: String, ref: String): String =
        if (base.isBlank()) ref else runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)
}
