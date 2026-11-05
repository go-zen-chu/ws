package xyz.amasuda.ws

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A starred page shown in the sidebar and on the home screen. */
data class Star(
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

    /** Whether [pageUrl] is this star's page or a page under it. */
    fun covers(pageUrl: String): Boolean {
        val base = url.trimEnd('/')
        return pageUrl == base || listOf("/", "?", "#").any { pageUrl.startsWith(base + it) }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) = Star(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            external = o.optBoolean("external"),
        )

        fun encode(stars: List<Star>): String =
            JSONArray().apply { stars.forEach { put(it.toJson()) } }.toString()

        fun decode(json: String): List<Star> {
            val arr = JSONArray(json)
            return List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }

        /** The star a page belongs to: the most specific one covering it (GitHub Pulls rather than GitHub). */
        fun ownerOf(pageUrl: String, stars: List<Star>): Star? =
            stars.filter { it.covers(pageUrl) }.maxByOrNull { it.url.trimEnd('/').length }

        /**
         * Turns the sites saved before stars existed into stars: drops the old Claude / Gemini
         * defaults and puts the new defaults the user does not have yet at the top.
         */
        fun migrateFromSites(sites: List<Star>): List<Star> {
            val kept = sites.filterNot { it.id in RETIRED_DEFAULT_IDS }
            val missing = DEFAULT_STARS.filter { d -> kept.none { it.id == d.id || it.url == d.url } }
            return missing + kept
        }

        private val RETIRED_DEFAULT_IDS = setOf("claude", "gemini")
    }
}

val DEFAULT_STARS = listOf(
    Star("github-notifications", "Notifications", "https://github.com/notifications"),
    Star("github-pulls", "Pull requests", "https://github.com/pulls"),
    Star("github", "GitHub", "https://github.com"),
    Star("github-dev", "github.dev", "https://github.dev"),
    Star("codespaces", "Codespaces", "https://github.com/codespaces"),
)

class StarStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<Star> {
        val json = prefs.getString(KEY_STARS, null) ?: return migrateSites()
        return runCatching { Star.decode(json) }.getOrDefault(DEFAULT_STARS)
    }

    fun save(stars: List<Star>) {
        prefs.edit().putString(KEY_STARS, Star.encode(stars)).remove(KEY_LEGACY_SITES).apply()
    }

    /** Moves the sites saved by older versions over to stars, once. */
    private fun migrateSites(): List<Star> {
        val json = prefs.getString(KEY_LEGACY_SITES, null) ?: return DEFAULT_STARS
        val stars = runCatching { Star.migrateFromSites(Star.decode(json)) }.getOrDefault(DEFAULT_STARS)
        save(stars)
        return stars
    }

    private companion object {
        const val KEY_STARS = "stars"
        const val KEY_LEGACY_SITES = "sites"
    }
}
