package xyz.amasuda.ws

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A frequently used site shown in the sidebar and on the home screen. */
data class Site(
    val id: String,
    val name: String,
    val url: String,
    /** Open in the system browser instead of a ws tab (e.g. sites that need Google sign-in). */
    val external: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("external", external)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) = Site(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            external = o.optBoolean("external"),
        )

        fun encode(sites: List<Site>): String =
            JSONArray().apply { sites.forEach { put(it.toJson()) } }.toString()

        fun decode(json: String): List<Site> {
            val arr = JSONArray(json)
            return List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }
    }
}

val DEFAULT_SITES = listOf(
    Site("github", "GitHub", "https://github.com"),
    Site("claude", "Claude", "https://claude.ai"),
    // Google blocks sign-in inside embedded WebViews, so Gemini opens in the system browser.
    Site("gemini", "Gemini", "https://gemini.google.com", external = true),
    Site("github-dev", "github.dev", "https://github.dev"),
    Site("codespaces", "Codespaces", "https://github.com/codespaces"),
)

class SiteStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<Site> {
        val json = prefs.getString(KEY_SITES, null) ?: return DEFAULT_SITES
        return runCatching { Site.decode(json) }.getOrDefault(DEFAULT_SITES)
    }

    fun save(sites: List<Site>) {
        prefs.edit().putString(KEY_SITES, Site.encode(sites)).apply()
    }

    private companion object {
        const val KEY_SITES = "sites"
    }
}
