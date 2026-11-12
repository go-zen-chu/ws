package xyz.amasuda.ws

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val ctrlK = KeyBinding.ctrl(KeyEvent.KEYCODE_K)

    private fun backupJson(keymap: String = "{}", stars: String = "[]") =
        """{"format":"ws-settings","version":1,"keymap":$keymap,"stars":$stars}"""

    @Test
    fun ifSettingsAreExportedTheyShouldImportUnchanged() {
        val keymap = Keymap.DEFAULT.assign(ShortcutAction.SHOW_SHORTCUTS, ctrlK).clear(ShortcutAction.CLOSE_TAB)
        val stars = listOf(Star("a", "A", "https://a.example", external = true))
        val decoded = SettingsBackup.decode(SettingsBackup(keymap, stars).encode())
        assertEquals(stars, decoded.stars)
        assertEquals(keymap.encode(), decoded.keymap.encode())
    }

    @Test
    fun ifAnotherJsonFileIsGivenItShouldBeRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.decode("""{"stars":[]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.decode(backupJson().replace("\"version\":1", "\"version\":99"))
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.decode(backupJson(stars = """[{"id":1}]""")) }
    }

    @Test
    fun ifKeysWithoutModifiersAreGivenTheyShouldBeDropped() {
        val plainK = KeyBinding(KeyEvent.KEYCODE_K).encode()
        val decoded = SettingsBackup.decode(backupJson(keymap = """{"SHOW_SHORTCUTS":["$plainK","${ctrlK.encode()}"]}"""))
        assertEquals(listOf(ctrlK), decoded.keymap.bindingsFor(ShortcutAction.SHOW_SHORTCUTS))
        assertEquals(ShortcutAction.NEW_TAB.defaults, decoded.keymap.bindingsFor(ShortcutAction.NEW_TAB))
    }

    @Test
    fun ifStarsAreNotWebPagesTheyShouldBeDropped() {
        val stars = """[{"id":"a","name":"A","url":"https://a.example"},{"id":"b","name":"B","url":"javascript:alert(1)"}]"""
        assertEquals(listOf("a"), SettingsBackup.decode(backupJson(stars = stars)).stars.map { it.id })
    }

    @Test
    fun ifSettingsAreExportedTheFileShouldNameItsFormat() {
        val o = JSONObject(SettingsBackup(Keymap.DEFAULT, DEFAULT_STARS).encode())
        assertEquals(SettingsBackup.FORMAT, o.getString("format"))
        assertTrue(o.getJSONArray("stars").length() == DEFAULT_STARS.size)
    }
}
