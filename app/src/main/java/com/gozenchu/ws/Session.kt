package com.gozenchu.ws

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TabState(val url: String, val title: String) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title)

    companion object {
        fun fromJson(o: JSONObject) = TabState(o.optString("url"), o.optString("title"))
    }
}

/**
 * A set of open tabs. The unnamed "current" session is what ws restores on launch;
 * named sessions are workspaces you can switch between.
 */
data class Session(
    val name: String?,
    val tabs: List<TabState>,
    val activeIndex: Int,
    val savedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name ?: JSONObject.NULL)
        .put("active", activeIndex)
        .put("savedAt", savedAt)
        .put("tabs", JSONArray().apply { tabs.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Session {
            val arr = o.optJSONArray("tabs") ?: JSONArray()
            return Session(
                name = if (o.isNull("name")) null else o.getString("name"),
                tabs = List(arr.length()) { TabState.fromJson(arr.getJSONObject(it)) },
                activeIndex = o.optInt("active"),
                savedAt = o.optLong("savedAt"),
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
