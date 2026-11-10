package xyz.amasuda.ws

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URI

/**
 * How tabs are drawn in the vertical tab list; stored with the session. Pinned tabs always show their icon,
 * and [ICON] narrows the list to a rail of icons.
 */
enum class TabDisplay(val labelRes: Int) {
    ICON_AND_TITLE(R.string.tab_display_icon_and_title),
    TITLE(R.string.tab_display_title),
    ICON(R.string.tab_display_icon);

    companion object {
        val DEFAULT = ICON_AND_TITLE

        /** Unknown or missing values (older sessions) fall back to [DEFAULT]. */
        fun decode(name: String?): TabDisplay = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Tab order rules: pinned tabs always come before the others. */
object TabOrder {

    /** Where a tab may be inserted near [desired]: pinned ones inside the pinned group, the rest after it. */
    fun insertionIndex(desired: Int, pinnedCount: Int, size: Int, pinned: Boolean): Int =
        if (pinned) desired.coerceIn(0, pinnedCount) else desired.coerceIn(pinnedCount, size)

    /** Puts pinned tabs first, keeping the order within each group (a newly pinned tab joins the end of the pinned ones). */
    fun <T> pinnedFirst(items: List<T>, isPinned: (T) -> Boolean): List<T> {
        val (pinned, others) = items.partition(isPinned)
        return pinned + others
    }
}

/** Finds tabs by title or URL. */
object TabSearch {

    /** Indexes of the tabs whose title or URL contains every word of [query], ignoring case. */
    fun filter(tabs: List<TabState>, query: String): List<Int> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tabs.indices.filter { i ->
            val text = "${tabs[i].title}\n${tabs[i].url}".lowercase()
            words.all { it in text }
        }
    }
}

/** Favicons are shared by every page of a host, so tabs restored without loading can still show one. */
object Favicons {
    private val HOST = Regex("^[a-z0-9.-]+$")

    /** A file-name-safe key for the URL's host, or null for URLs without a usable host. */
    fun keyFor(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
        return host.takeIf { HOST.matches(it) }
    }

    /** The letter shown when there is no favicon yet: the host's first letter, else the title's. */
    fun fallbackLetter(url: String, title: String): String {
        val host = keyFor(url)?.removePrefix("www.")
        val source = host ?: title.trim()
        return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    }
}

/** Favicons kept in the cache directory, one PNG per host (see [Favicons.keyFor]). */
class FaviconStore(context: Context) {
    private val dir = File(context.cacheDir, "favicons")
    private val loaded = mutableMapOf<String, Bitmap?>()

    fun load(url: String): Bitmap? {
        val key = Favicons.keyFor(url) ?: return null
        // Hosts without a stored icon are remembered as null too, so redraws do not hit the disk.
        if (key !in loaded) loaded[key] = BitmapFactory.decodeFile(File(dir, "$key.png").path)
        return loaded[key]
    }

    /** Returns false when the icon is already the stored one, so callers can skip redrawing. */
    fun save(url: String, icon: Bitmap): Boolean {
        val key = Favicons.keyFor(url) ?: return false
        if (load(url)?.sameAs(icon) == true) return false
        loaded[key] = icon
        // The cache may be cleared at any time; the icon simply comes back on the next visit.
        runCatching {
            dir.mkdirs()
            File(dir, "$key.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return true
    }
}
