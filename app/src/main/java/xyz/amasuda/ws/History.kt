package xyz.amasuda.ws

/** One page in a tab's back / forward history. */
data class HistoryEntry(val title: String, val url: String)

/** A page the tab can jump to: [steps] for `WebView.goBackOrForward` (negative is back). */
data class HistoryJump(val steps: Int, val label: String, val url: String)

/** Builds the lists shown when the back / forward buttons are long-pressed. */
object History {
    /** How many pages each list shows; older ones are reached from the farthest entry. */
    const val LIMIT = 20

    /** The pages behind the current one ([entries] oldest first), nearest first. */
    fun back(entries: List<HistoryEntry>, current: Int, limit: Int = LIMIT): List<HistoryJump> =
        jumps(entries, current, -1, limit)

    /** The pages ahead of the current one ([entries] oldest first), nearest first. */
    fun forward(entries: List<HistoryEntry>, current: Int, limit: Int = LIMIT): List<HistoryJump> =
        jumps(entries, current, 1, limit)

    /** The pages from [current] towards [direction] (-1 or 1), nearest first. */
    private fun jumps(entries: List<HistoryEntry>, current: Int, direction: Int, limit: Int): List<HistoryJump> {
        if (current !in entries.indices) return emptyList()
        return generateSequence(current + direction) { it + direction }
            .takeWhile { it in entries.indices }
            .take(limit.coerceAtLeast(0))
            .map { index ->
                val entry = entries[index]
                HistoryJump(index - current, entry.title.ifBlank { entry.url }, entry.url)
            }
            .toList()
    }
}
