package xyz.amasuda.ws

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** The settings that move between devices as a file: keyboard shortcuts and stars. */
class SettingsBackup(val keymap: Keymap, val stars: List<Star>) {

    fun encode(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("keymap", JSONObject(keymap.encode()))
        .put("stars", JSONArray(Star.encode(stars)))
        .toString(2)

    companion object {
        const val FORMAT = "ws-settings"
        const val VERSION = 1
        const val MIME_TYPE = "application/json"

        /**
         * Reads an exported file. Throws [IllegalArgumentException] for anything that is not one; keys that
         * could not be assigned in the app and stars that are not web pages are dropped rather than imported.
         */
        fun decode(json: String): SettingsBackup {
            val o = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IllegalArgumentException("not a JSON object: ${e.message}", e)
            }
            require(o.optString("format") == FORMAT) { "not a $FORMAT file" }
            require(o.optInt("version") in 1..VERSION) { "unsupported version ${o.opt("version")}" }
            try {
                val keymap = o.optJSONObject("keymap")?.let { usableKeysOnly(Keymap.decode(it.toString())) } ?: Keymap.DEFAULT
                val stars = o.optJSONArray("stars")?.let { Star.decode(it.toString()) }.orEmpty().filter { isWebPage(it.url) }
                return SettingsBackup(keymap, stars.distinctBy { it.id })
            } catch (e: JSONException) {
                throw IllegalArgumentException("broken settings: ${e.message}", e)
            }
        }

        private fun usableKeysOnly(keymap: Keymap): Keymap =
            ShortcutAction.entries.fold(Keymap.DEFAULT) { result, action ->
                val keys = keymap.bindingsFor(action).filter { it.isUsableShortcut() }
                if (keys == action.defaults) result else keys.fold(result.clear(action)) { acc, key -> acc.assign(action, key) }
            }

        private fun isWebPage(url: String): Boolean =
            runCatching { URI(url).scheme }.getOrNull()?.lowercase() in setOf("http", "https")
    }
}
