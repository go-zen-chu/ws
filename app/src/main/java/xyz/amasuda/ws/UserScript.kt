package xyz.amasuda.ws

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** When a user script runs on a page (`@run-at`). */
enum class RunAt(val key: String) {
    DOCUMENT_START("document-start"),
    DOCUMENT_END("document-end"),
    DOCUMENT_IDLE("document-idle");

    companion object {
        /** Unknown values (e.g. Tampermonkey's `context-menu`) run when the page has loaded, the default. */
        fun of(value: String?): RunAt = when (value?.trim()?.lowercase()) {
            "document-start" -> DOCUMENT_START
            "document-end", "document-body" -> DOCUMENT_END
            else -> DOCUMENT_IDLE
        }
    }
}

/** The privileged GM_* APIs a script must `@grant`; each value is the grant names that unlock it. */
enum class GmApi(vararg val grantNames: String) {
    VALUES(
        "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
        "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues",
    ),
    XHR("GM_xmlhttpRequest", "GM.xmlHttpRequest"),
    OPEN_TAB("GM_openInTab", "GM.openInTab"),
    CLIPBOARD("GM_setClipboard", "GM.setClipboard"),
    NOTIFICATION("GM_notification", "GM.notification"),
    MENU("GM_registerMenuCommand", "GM.registerMenuCommand", "GM_unregisterMenuCommand"),
}

/** The `// ==UserScript==` metadata block of a user script (the Greasemonkey / Tampermonkey format). */
data class UserScriptMeta(
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val excludeMatches: List<String> = emptyList(),
    val runAt: RunAt = RunAt.DOCUMENT_IDLE,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
    val connects: List<String> = emptyList(),
    val downloadUrl: String = "",
    val updateUrl: String = "",
    val block: String = "",
) {
    /** Name and namespace identify a script, as in Tampermonkey, so installing it again updates it. */
    val id: String get() = UUID.nameUUIDFromBytes("$namespace\n$name".toByteArray()).toString()

    /**
     * Whether the script runs on [url]: on web pages its `@match` / `@include` cover and its excludes do not.
     * Like Greasemonkey, a script with neither `@match` nor `@include` runs everywhere.
     */
    fun runsOn(url: String): Boolean {
        if (!UrlPatterns.isWeb(url)) return false
        if (excludes.any { UrlPatterns.include(it, url) } || excludeMatches.any { UrlPatterns.match(it, url) }) return false
        if (matches.isEmpty() && includes.isEmpty()) return true
        return matches.any { UrlPatterns.match(it, url) } || includes.any { UrlPatterns.include(it, url) }
    }

    /** Without any `@grant` a script gets every API, like Greasemonkey 3; `@grant none` gets none. */
    fun allows(api: GmApi): Boolean = grants.isEmpty() || api.grantNames.any { it in grants }

    /** Where to look for a newer version, falling back to where it was installed from. */
    fun updateSource(installedFrom: String): String = downloadUrl.ifBlank { installedFrom }

    companion object {
        private val BLOCK = Regex("//[ \\t]*==UserScript==[^\\n]*\\n([\\s\\S]*?)//[ \\t]*==/UserScript==")
        private val LINE = Regex("^\\s*//\\s*@(\\S+)(?:\\s+(.*?))?\\s*$")
        private val SPACES = Regex("\\s+")

        /** Reads the metadata block. Throws [IllegalArgumentException] for code without one or without `@name`. */
        fun parse(source: String): UserScriptMeta {
            val block = BLOCK.find(source.replace("\r\n", "\n"))
                ?: throw IllegalArgumentException("no ==UserScript== block")
            val entries = block.groupValues[1].lines().mapNotNull { LINE.matchEntire(it) }
                .map { it.groupValues[1] to it.groupValues[2] }

            fun all(key: String) = entries.filter { it.first == key }.map { it.second }.filter { it.isNotEmpty() }
            fun first(key: String) = all(key).firstOrNull().orEmpty()

            val name = first("name")
            require(name.isNotBlank()) { "no @name" }
            return UserScriptMeta(
                name = name,
                namespace = first("namespace"),
                version = first("version"),
                description = first("description"),
                author = first("author"),
                homepage = first("homepageURL").ifEmpty { first("homepage") },
                matches = all("match"),
                includes = all("include"),
                excludes = all("exclude"),
                excludeMatches = all("exclude-match"),
                runAt = RunAt.of(first("run-at")),
                grants = all("grant"),
                requires = all("require"),
                resources = all("resource").mapNotNull { r ->
                    r.split(SPACES, limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap(),
                connects = all("connect"),
                downloadUrl = first("downloadURL"),
                updateUrl = first("updateURL"),
                block = block.value,
            )
        }
    }
}

/** URL patterns of user scripts: `@match` (Chrome match patterns) and `@include` / `@exclude` (globs or /regex/). */
object UrlPatterns {
    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^#]*)")
    private val MATCH = Regex("^(\\*|[a-zA-Z][a-zA-Z0-9+.-]*)://(\\*|\\*\\.[^/*]+|[^/*]+)(/.*)$")

    fun isWeb(url: String): Boolean = URL.find(url)?.groupValues?.get(1)?.lowercase() in setOf("http", "https")

    /** Chrome match pattern semantics: `*` as the scheme means http or https; `*.host` covers the host and its subdomains. */
    fun match(pattern: String, url: String): Boolean {
        val parts = URL.find(url) ?: return false
        val scheme = parts.groupValues[1].lowercase()
        val host = authorityHost(parts.groupValues[2])
        val path = parts.groupValues[3].ifEmpty { "/" }
        if (pattern == "<all_urls>") return scheme == "http" || scheme == "https"
        val p = MATCH.matchEntire(pattern.trim()) ?: return false
        val (pScheme, pHost, pPath) = p.destructured
        val schemeOk = if (pScheme == "*") scheme == "http" || scheme == "https" else pScheme.equals(scheme, ignoreCase = true)
        val hostOk = when {
            pHost == "*" -> true
            pHost.startsWith("*.") -> pHost.substring(2).lowercase().let { host == it || host.endsWith(".$it") }
            else -> authorityHost(pHost) == host
        }
        return schemeOk && hostOk && glob(pPath).matches(path)
    }

    /** Greasemonkey `@include` semantics: a glob where `*` matches anything, or a regular expression between slashes. */
    fun include(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) {
            return runCatching { Regex(p.substring(1, p.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(url) }
                .getOrDefault(false)
        }
        return glob(p, RegexOption.IGNORE_CASE).matches(url)
    }

    private fun glob(pattern: String, vararg options: RegexOption): Regex =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }, options.toSet())

    /** The lower-case host of [url], or "" when it has none. */
    fun hostOf(url: String): String = URL.find(url)?.groupValues?.get(2)?.let { authorityHost(it) }.orEmpty()

    private fun authorityHost(authority: String): String =
        authority.substringAfterLast('@').let { if (it.startsWith("[")) it.substringBefore("]") + "]" else it.substringBefore(':') }
            .lowercase()
}

/** A downloaded `@resource`, kept as bytes so `GM_getResourceURL` can hand out a data: URL. */
data class ScriptResource(val url: String, val mimeType: String, val base64: String)

/** An installed user script with everything it needs to run offline: its `@require`s and `@resource`s. */
data class UserScript(
    val source: String,
    val requires: List<String> = emptyList(),
    val resources: Map<String, ScriptResource> = emptyMap(),
    val sourceUrl: String = "",
    val enabled: Boolean = true,
    /** Hosts the user allowed `GM_xmlhttpRequest` to reach although `@connect` does not list them. */
    val approvedHosts: Set<String> = emptySet(),
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val meta: UserScriptMeta = UserScriptMeta.parse(source)
    val id: String get() = meta.id

    /**
     * Whether `GM_xmlhttpRequest` may reach [host] from a page on [pageHost]: the page's own host, a host
     * `@connect` covers (`*`, `self`, a domain and its subdomains), or one the user approved.
     */
    fun mayConnect(host: String, pageHost: String): Boolean {
        val h = host.lowercase()
        if (h == pageHost.lowercase() || h in approvedHosts) return true
        return meta.connects.any { rule ->
            val r = rule.trim().lowercase()
            r == "*" || (r == "self" && h == pageHost.lowercase()) || h == r || h.endsWith(".$r")
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("source", source)
        .put("requires", JSONArray(requires))
        .put("resources", JSONObject().apply {
            resources.forEach { (name, r) ->
                put(name, JSONObject().put("url", r.url).put("mimeType", r.mimeType).put("base64", r.base64))
            }
        })
        .put("sourceUrl", sourceUrl)
        .put("enabled", enabled)
        .put("approvedHosts", JSONArray(approvedHosts.sorted()))
        .put("installedAt", installedAt)
        .put("updatedAt", updatedAt)

    companion object {
        const val FORMAT = "ws-userscript"
        const val VERSION = 1

        /** Reads a stored script. Throws [IllegalArgumentException] for anything else. */
        fun fromJson(json: String): UserScript = try {
            val o = JSONObject(json)
            require(o.optString("format") == FORMAT) { "not a $FORMAT file" }
            val requires = o.optJSONArray("requires") ?: JSONArray()
            val resources = o.optJSONObject("resources") ?: JSONObject()
            val hosts = o.optJSONArray("approvedHosts") ?: JSONArray()
            UserScript(
                source = o.getString("source"),
                requires = List(requires.length()) { requires.getString(it) },
                resources = resources.keys().asSequence().associateWith { name ->
                    resources.getJSONObject(name).let { ScriptResource(it.getString("url"), it.optString("mimeType"), it.getString("base64")) }
                },
                sourceUrl = o.optString("sourceUrl"),
                enabled = o.optBoolean("enabled", true),
                approvedHosts = List(hosts.length()) { hosts.getString(it) }.toSet(),
                installedAt = o.optLong("installedAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("broken user script: ${e.message}", e)
        }

        /** Whether a link points at a user script to offer for installing, like Tampermonkey's `*.user.js` rule. */
        fun isInstallUrl(url: String): Boolean =
            UrlPatterns.isWeb(url) && url.substringBefore('#').substringBefore('?').endsWith(".user.js", ignoreCase = true)
    }
}
