package xyz.amasuda.ws

import org.json.JSONObject
import java.util.UUID

/** A named, collapsible group of adjacent tabs in the vertical tab list; stored with the session. */
data class TabGroup(val id: String, val name: String, val collapsed: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).apply {
        if (collapsed) put("collapsed", true)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) = TabGroup(o.getString("id"), o.optString("name"), o.optBoolean("collapsed"))
    }
}

/** One tab's place in the list: whether it is pinned and which group it belongs to. */
data class TabSlot(val pinned: Boolean, val group: String?)

/** A tab in a new arrangement: its index in the old list ([from]) and the group it ends up in. */
data class Placed(val from: Int, val group: String?)

/** A line of the vertical tab list: a group's header, or a tab by its index in the tab list. */
sealed interface TabRow {
    data class Header(val group: String, val firstIndex: Int, val size: Int) : TabRow
    data class Tab(val index: Int, val group: String?) : TabRow
}

/** Where a dragged tab lands in the vertical tab list. */
sealed interface TabDrop {
    data class Before(val index: Int) : TabDrop
    data class After(val index: Int) : TabDrop

    /** On top of a tab: joins its group, or makes a new group with it. */
    data class Onto(val index: Int) : TabDrop

    /** On a group's header: becomes the group's first tab. */
    data class IntoGroup(val group: String) : TabDrop
    data object End : TabDrop
}

/**
 * Tab group rules. The tabs of a group are always next to each other and pinned tabs are never grouped,
 * so a group is drawn as a header followed by its tabs.
 */
object TabGroups {

    /** The rows to draw. A collapsed group hides its tabs, except the active one. */
    fun rows(slots: List<TabSlot>, groups: List<TabGroup>, activeIndex: Int): List<TabRow> {
        val collapsed = groups.filter { it.collapsed }.map { it.id }.toSet()
        val rows = mutableListOf<TabRow>()
        slots.forEachIndexed { i, slot ->
            val group = slot.group
            if (group != null && slots.getOrNull(i - 1)?.group != group) {
                rows += TabRow.Header(group, i, slots.drop(i).takeWhile { it.group == group }.size)
            }
            if (group == null || group !in collapsed || i == activeIndex) rows += TabRow.Tab(i, group)
        }
        return rows
    }

    /**
     * The drop target at [y], given the rows drawn and the vertical extent ([IntRange] of top until bottom) of each.
     * The middle half of a tab means "onto" it; the upper half of a header means "before the group".
     */
    fun dropAt(rows: List<TabRow>, spans: List<IntRange>, y: Int): TabDrop {
        rows.forEachIndexed { i, row ->
            val span = spans[i]
            if (y > span.last) return@forEachIndexed
            val offset = (y - span.first).coerceAtLeast(0)
            val height = (span.last - span.first).coerceAtLeast(1)
            return when (row) {
                is TabRow.Header -> if (offset < height / 2) TabDrop.Before(row.firstIndex) else TabDrop.IntoGroup(row.group)
                is TabRow.Tab -> when {
                    offset < height / 4 -> TabDrop.Before(row.index)
                    offset > height * 3 / 4 -> TabDrop.After(row.index)
                    else -> TabDrop.Onto(row.index)
                }
            }
        }
        return TabDrop.End
    }

    /**
     * The arrangement after dropping the tab at [from] on [target], or null when nothing changes.
     * A tab dropped between two tabs of the same group joins it; anywhere else it leaves its group.
     * Dropping onto an ungrouped tab groups both under [newGroupId].
     */
    fun drop(slots: List<TabSlot>, from: Int, target: TabDrop, newGroupId: String): List<Placed>? {
        val moving = slots[from]
        val rest = slots.indices.filter { it != from }
        var joined: String? = null
        var newGroupWith = -1
        val desired = when (target) {
            is TabDrop.Before -> rest.indexOf(target.index)
            is TabDrop.After -> rest.indexOf(target.index).let { if (it < 0) -1 else it + 1 }
            is TabDrop.Onto -> {
                val other = slots.getOrNull(target.index) ?: return null
                if (!moving.pinned && !other.pinned) {
                    joined = other.group ?: newGroupId.also { newGroupWith = target.index }
                }
                rest.indexOf(target.index).let { if (it < 0) -1 else it + 1 }
            }
            is TabDrop.IntoGroup -> rest.indexOfFirst { slots[it].group == target.group }.also { joined = target.group }
            TabDrop.End -> rest.size
        }
        if (desired < 0) return null
        val pinnedCount = rest.count { slots[it].pinned }
        val position = if (moving.pinned) desired.coerceIn(0, pinnedCount) else desired.coerceAtLeast(pinnedCount)
        val group = when {
            moving.pinned -> null
            joined != null -> joined
            else -> gapGroup(slots, rest, position)
        }
        val order = rest.toMutableList().apply { add(position, from) }
        val placed = order.map { i ->
            Placed(i, if (i == from) group else if (i == newGroupWith) newGroupId else slots[i].group)
        }
        val unchanged = placed.withIndex().all { (i, p) -> p.from == i && p.group == slots[i].group }
        return if (unchanged) null else placed
    }

    /** Takes the tab at [index] out of its group, moving it after the group so the group stays together. */
    fun removeFromGroup(slots: List<TabSlot>, index: Int): List<Placed> {
        val group = slots[index].group ?: return slots.mapIndexed { i, s -> Placed(i, s.group) }
        val last = slots.indexOfLast { it.group == group }
        if (last == index) return slots.mapIndexed { i, s -> Placed(i, if (i == index) null else s.group) }
        return requireNotNull(drop(slots, index, TabDrop.After(last), ""))
    }

    /**
     * The group for a tab inserted between tabs in groups [previous] and [next]. Inside a group it must join;
     * otherwise it keeps [wanted] (its opener's group) only when it ends up next to that group.
     */
    fun groupForInsert(previous: String?, next: String?, wanted: String?): String? = when {
        previous != null && previous == next -> previous
        wanted != null && (wanted == previous || wanted == next) -> wanted
        else -> null
    }

    /**
     * Repairs stored tabs: unknown groups and groups on pinned tabs are dropped, and the tabs of each group are
     * gathered where the group first appears.
     */
    fun normalize(slots: List<TabSlot>, groups: List<TabGroup>): List<Placed> {
        val known = groups.map { it.id }.toSet()
        val groupOf = slots.map { s -> s.group?.takeIf { !s.pinned && it in known } }
        val placed = mutableListOf<Placed>()
        val done = mutableSetOf<String>()
        groupOf.forEachIndexed { i, group ->
            when {
                group == null -> placed += Placed(i, null)
                done.add(group) -> groupOf.indices.filter { groupOf[it] == group }.forEach { placed += Placed(it, group) }
            }
        }
        return placed
    }

    /** The groups that still have tabs, in their stored order. */
    fun prune(groups: List<TabGroup>, slots: List<TabSlot>): List<TabGroup> {
        val used = slots.mapNotNull { it.group }.toSet()
        return groups.filter { it.id in used }
    }

    private fun gapGroup(slots: List<TabSlot>, rest: List<Int>, position: Int): String? {
        val previous = rest.getOrNull(position - 1)?.let { slots[it].group }
        val next = rest.getOrNull(position)?.let { slots[it].group }
        return if (previous != null && previous == next) previous else null
    }
}
