package xyz.amasuda.ws

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** [textZoom] is the tab's text size in percent (WebSettings.textZoom); [pinned] keeps it at the start of the strip. */
data class TabState(
    val url: String,
    val title: String,
    val textZoom: Int = TextZoom.DEFAULT,
    val pinned: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).apply {
        // Left out at their defaults so sessions saved before these existed look the same.
        if (textZoom != TextZoom.DEFAULT) put("textZoom", textZoom)
        if (pinned) put("pinned", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = TabState(
            o.optString("url"),
            o.optString("title"),
            TextZoom.clamp(o.optInt("textZoom", TextZoom.DEFAULT)),
            o.optBoolean("pinned"),
        )
    }
}

/** Text size steps, like Chrome's zoom levels. */
object TextZoom {
    const val DEFAULT = 100
    val LEVELS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300)

    fun clamp(percent: Int): Int = percent.coerceIn(LEVELS.first(), LEVELS.last())

    fun larger(percent: Int): Int = LEVELS.firstOrNull { it > percent } ?: LEVELS.last()

    fun smaller(percent: Int): Int = LEVELS.lastOrNull { it < percent } ?: LEVELS.first()
}

/**
 * A set of open tabs. The unnamed "current" session is what ws restores on launch;
 * named sessions are workspaces you can switch between. [tabDisplay] is how its tab strip looks.
 */
data class Session(
    val name: String?,
    val tabs: List<TabState>,
    val activeIndex: Int,
    val savedAt: Long,
    val tabDisplay: TabDisplay = TabDisplay.DEFAULT,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name ?: JSONObject.NULL)
        .put("active", activeIndex)
        .put("savedAt", savedAt)
        .put("tabDisplay", tabDisplay.name)
        .put("tabs", JSONArray().apply { tabs.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Session {
            val arr = o.optJSONArray("tabs") ?: JSONArray()
            return Session(
                name = if (o.isNull("name")) null else o.getString("name"),
                tabs = List(arr.length()) { TabState.fromJson(arr.getJSONObject(it)) },
                activeIndex = o.optInt("active"),
                savedAt = o.optLong("savedAt"),
                tabDisplay = TabDisplay.decode(o.optString("tabDisplay").ifEmpty { null }),
            )
        }

        fun encode(sessions: List<Session>): String =
            JSONArray().apply { sessions.forEach { put(it.toJson()) } }.toString()

        fun decode(json: String): List<Session> {
            val arr = JSONArray(json)
            return List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }

        /** Replaces the session with the same name (or adds it), newest first. */
        fun upsert(sessions: List<Session>, session: Session): List<Session> =
            (listOf(session) + sessions.filter { it.name != session.name }).sortedByDescending { it.savedAt }
    }
}

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCurrent(): Session? {
        val json = prefs.getString(KEY_CURRENT, null) ?: return null
        return runCatching { Session.fromJson(JSONObject(json)) }.getOrNull()
    }

    /** Saves the session restored on next launch; a named session is also kept in the list. */
    fun saveCurrent(session: Session) {
        val editor = prefs.edit().putString(KEY_CURRENT, session.toJson().toString())
        if (session.name != null) editor.putString(KEY_NAMED, Session.encode(Session.upsert(listNamed(), session)))
        editor.apply()
    }

    fun listNamed(): List<Session> {
        val json = prefs.getString(KEY_NAMED, null) ?: return emptyList()
        return runCatching { Session.decode(json) }.getOrDefault(emptyList())
    }

    fun findNamed(name: String): Session? = listNamed().firstOrNull { it.name == name }

    fun saveNamed(session: Session) {
        requireNotNull(session.name)
        prefs.edit().putString(KEY_NAMED, Session.encode(Session.upsert(listNamed(), session))).apply()
    }

    fun deleteNamed(name: String) {
        prefs.edit().putString(KEY_NAMED, Session.encode(listNamed().filter { it.name != name })).apply()
    }

    private companion object {
        const val KEY_CURRENT = "session.current"
        const val KEY_NAMED = "session.named"
    }
}
