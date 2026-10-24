package com.gozenchu.ws

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapTest {
    private val ctrlT = KeyBinding.ctrl(KeyEvent.KEYCODE_T)
    private val ctrlK = KeyBinding.ctrl(KeyEvent.KEYCODE_K)

    @Test
    fun defaultsResolveWithExactModifiers() {
        val keymap = Keymap.DEFAULT
        assertEquals(ShortcutAction.NEW_TAB, keymap.actionFor(ctrlT))
        assertEquals(
            ShortcutAction.REOPEN_TAB,
            keymap.actionFor(KeyBinding(KeyEvent.KEYCODE_T, KeyBinding.CTRL or KeyBinding.SHIFT)),
        )
        assertEquals(ShortcutAction.RELOAD, keymap.actionFor(KeyBinding(KeyEvent.KEYCODE_F5)))
        assertNull(keymap.actionFor(KeyBinding(KeyEvent.KEYCODE_T)))
    }

    @Test
    fun defaultsDoNotCollide() {
        val all = ShortcutAction.entries.flatMap { it.defaults }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun assignMovesKeyFromOtherAction() {
        val keymap = Keymap.DEFAULT.assign(ShortcutAction.OPEN_TERMUX, ctrlT)
        assertEquals(ShortcutAction.OPEN_TERMUX, keymap.actionFor(ctrlT))
        assertTrue(keymap.bindingsFor(ShortcutAction.NEW_TAB).isEmpty())
    }

    @Test
    fun resetTakesDefaultBack() {
        val keymap = Keymap.DEFAULT.assign(ShortcutAction.OPEN_TERMUX, ctrlT).reset(ShortcutAction.NEW_TAB)
        assertEquals(ShortcutAction.NEW_TAB, keymap.actionFor(ctrlT))
        assertEquals(ShortcutAction.OPEN_TERMUX.defaults, keymap.bindingsFor(ShortcutAction.OPEN_TERMUX))
    }

    @Test
    fun encodesOnlyChanges() {
        assertEquals("{}", Keymap.DEFAULT.encode())
        val keymap = Keymap.DEFAULT.assign(ShortcutAction.SHOW_SHORTCUTS, ctrlK).clear(ShortcutAction.CLOSE_TAB)
        val decoded = Keymap.decode(keymap.encode())
        assertEquals(listOf(ctrlK), decoded.bindingsFor(ShortcutAction.SHOW_SHORTCUTS))
        assertTrue(decoded.bindingsFor(ShortcutAction.CLOSE_TAB).isEmpty())
        assertEquals(ShortcutAction.NEW_TAB.defaults, decoded.bindingsFor(ShortcutAction.NEW_TAB))
    }

    @Test
    fun labelsAndUsability() {
        assertEquals("Ctrl+Shift+Tab", KeyBinding(KeyEvent.KEYCODE_TAB, KeyBinding.CTRL or KeyBinding.SHIFT).label())
        assertEquals("Alt+←", KeyBinding(KeyEvent.KEYCODE_DPAD_LEFT, KeyBinding.ALT).label())
        assertTrue(KeyBinding(KeyEvent.KEYCODE_F11).isUsableShortcut())
        assertFalse(KeyBinding(KeyEvent.KEYCODE_A, KeyBinding.SHIFT).isUsableShortcut())
    }
}
