package xyz.amasuda.ws

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** A key plus the exact set of modifiers that must be held. */
data class KeyBinding(val keyCode: Int, val modifiers: Int = 0) {

    fun label(): String = buildString {
        if (modifiers and CTRL != 0) append("Ctrl+")
        if (modifiers and ALT != 0) append("Alt+")
        if (modifiers and SHIFT != 0) append("Shift+")
        if (modifiers and META != 0) append("Meta+")
        append(keyName(keyCode))
    }

    fun encode(): String = "$keyCode:$modifiers"

    /** Plain keys (or Shift+key) would swallow typing in pages, so they need Ctrl, Alt or Meta, or be an F-key. */
    fun isUsableShortcut(): Boolean =
        modifiers and (CTRL or ALT or META) != 0 || keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12

    companion object {
        const val CTRL = 1
        const val SHIFT = 2
        const val ALT = 4
        const val META = 8

        fun ctrl(keyCode: Int) = KeyBinding(keyCode, CTRL)

        fun of(event: KeyEvent): KeyBinding {
            var mods = 0
            if (event.isCtrlPressed) mods = mods or CTRL
            if (event.isShiftPressed) mods = mods or SHIFT
            if (event.isAltPressed) mods = mods or ALT
            if (event.isMetaPressed) mods = mods or META
            return KeyBinding(event.keyCode, mods)
        }

        fun decode(s: String): KeyBinding? {
            val parts = s.split(':')
            if (parts.size != 2) return null
            val key = parts[0].toIntOrNull() ?: return null
            val mods = parts[1].toIntOrNull() ?: return null
            return KeyBinding(key, mods)
        }

        private fun keyName(code: Int): String = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${code - KeyEvent.KEYCODE_F1 + 1}"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_SEMICOLON -> ";"
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            KeyEvent.KEYCODE_MINUS -> "-"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_GRAVE -> "`"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        }
    }
}

/** Everything a keyboard shortcut can do, with its default keys. */
/** [labelRes] is a string resource; TAB_1..TAB_8 share one that takes the tab number. */
enum class ShortcutAction(val labelRes: Int, val defaults: List<KeyBinding>) {
    NEW_TAB(R.string.new_tab, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_T))),
    CLOSE_TAB(R.string.action_close_tab, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_W))),
    REOPEN_TAB(R.string.action_reopen_tab, listOf(KeyBinding(KeyEvent.KEYCODE_T, KeyBinding.CTRL or KeyBinding.SHIFT))),
    NEXT_TAB(R.string.action_next_tab, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_TAB))),
    PREV_TAB(R.string.action_prev_tab, listOf(KeyBinding(KeyEvent.KEYCODE_TAB, KeyBinding.CTRL or KeyBinding.SHIFT))),
    TAB_1(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_1))),
    TAB_2(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_2))),
    TAB_3(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_3))),
    TAB_4(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_4))),
    TAB_5(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_5))),
    TAB_6(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_6))),
    TAB_7(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_7))),
    TAB_8(R.string.action_tab_n, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_8))),
    LAST_TAB(R.string.action_last_tab, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_9))),
    SEARCH_TABS(R.string.search_tabs, listOf(KeyBinding(KeyEvent.KEYCODE_A, KeyBinding.CTRL or KeyBinding.SHIFT))),
    TOGGLE_PIN(R.string.action_toggle_pin, emptyList()),
    FOCUS_ADDRESS(R.string.action_focus_address, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_L))),
    RELOAD(R.string.reload, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_R), KeyBinding(KeyEvent.KEYCODE_F5))),
    BACK(R.string.back, listOf(KeyBinding(KeyEvent.KEYCODE_DPAD_LEFT, KeyBinding.ALT))),
    FORWARD(R.string.forward, listOf(KeyBinding(KeyEvent.KEYCODE_DPAD_RIGHT, KeyBinding.ALT))),
    ZOOM_IN(R.string.action_zoom_in, listOf(
        KeyBinding.ctrl(KeyEvent.KEYCODE_EQUALS),
        KeyBinding(KeyEvent.KEYCODE_EQUALS, KeyBinding.CTRL or KeyBinding.SHIFT), // Ctrl++ on US keyboards
        KeyBinding(KeyEvent.KEYCODE_SEMICOLON, KeyBinding.CTRL or KeyBinding.SHIFT), // Ctrl++ on JIS keyboards
        KeyBinding.ctrl(KeyEvent.KEYCODE_NUMPAD_ADD),
    )),
    ZOOM_OUT(R.string.action_zoom_out, listOf(
        KeyBinding.ctrl(KeyEvent.KEYCODE_MINUS),
        KeyBinding.ctrl(KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
    )),
    ZOOM_RESET(R.string.action_zoom_reset, listOf(KeyBinding.ctrl(KeyEvent.KEYCODE_0), KeyBinding.ctrl(KeyEvent.KEYCODE_NUMPAD_0))),
    TOGGLE_SIDEBAR(R.string.action_toggle_sidebar, listOf(KeyBinding(KeyEvent.KEYCODE_B, KeyBinding.CTRL or KeyBinding.SHIFT))),
    TOGGLE_FULLSCREEN(R.string.action_toggle_fullscreen, listOf(KeyBinding(KeyEvent.KEYCODE_F11))),
    SAVE_SESSION(R.string.save_session, listOf(KeyBinding(KeyEvent.KEYCODE_S, KeyBinding.CTRL or KeyBinding.SHIFT))),
    OPEN_TERMUX(R.string.open_termux, listOf(KeyBinding(KeyEvent.KEYCODE_T, KeyBinding.CTRL or KeyBinding.ALT))),
    // No default: Ctrl+/ and F1 are taken by VS Code on github.dev.
    SHOW_SHORTCUTS(R.string.shortcut_settings, emptyList()),
}

/** The user's key bindings: defaults plus whatever they changed. */
class Keymap(private val bindings: Map<ShortcutAction, List<KeyBinding>>) {

    fun bindingsFor(action: ShortcutAction): List<KeyBinding> = bindings[action] ?: action.defaults

    fun actionFor(binding: KeyBinding): ShortcutAction? =
        ShortcutAction.entries.firstOrNull { binding in bindingsFor(it) }

    fun label(action: ShortcutAction): String? = bindingsFor(action).firstOrNull()?.label()

    /** Adds a key to an action, taking it away from any action that used it. */
    fun assign(action: ShortcutAction, binding: KeyBinding): Keymap {
        val updated = ShortcutAction.entries.associateWith { bindingsFor(it) - binding }.toMutableMap()
        updated[action] = updated.getValue(action) + binding
        return Keymap(updated)
    }

    fun clear(action: ShortcutAction): Keymap = Keymap(bindings + (action to emptyList()))

    fun reset(action: ShortcutAction): Keymap =
        action.defaults.fold(Keymap(bindings + (action to emptyList()))) { keymap, key -> keymap.assign(action, key) }

    /** Stores only what differs from the defaults, so new actions pick up their defaults. */
    fun encode(): String {
        val json = JSONObject()
        for (action in ShortcutAction.entries) {
            val keys = bindingsFor(action)
            if (keys != action.defaults) json.put(action.name, JSONArray().apply { keys.forEach { put(it.encode()) } })
        }
        return json.toString()
    }

    companion object {
        val DEFAULT = Keymap(emptyMap())

        fun decode(json: String): Keymap {
            val o = JSONObject(json)
            val bindings = ShortcutAction.entries.filter { o.has(it.name) }.associateWith { action ->
                val arr = o.getJSONArray(action.name)
                List(arr.length()) { KeyBinding.decode(arr.getString(it)) }.filterNotNull()
            }
            return Keymap(bindings)
        }
    }
}

class KeymapStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Keymap {
        val json = prefs.getString(KEY_KEYMAP, null) ?: return Keymap.DEFAULT
        return runCatching { Keymap.decode(json) }.getOrDefault(Keymap.DEFAULT)
    }

    fun save(keymap: Keymap) {
        prefs.edit().putString(KEY_KEYMAP, keymap.encode()).apply()
    }

    private companion object {
        const val KEY_KEYMAP = "keymap"
    }
}
