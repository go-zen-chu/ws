package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabGroupsTest {
    // 0: pinned, 1-2: group "a", 3: ungrouped, 4-5: group "b".
    private val slots = listOf(
        TabSlot(true, null),
        TabSlot(false, "a"),
        TabSlot(false, "a"),
        TabSlot(false, null),
        TabSlot(false, "b"),
        TabSlot(false, "b"),
    )
    private val groups = listOf(TabGroup("a", "A"), TabGroup("b", "B"))

    private fun order(placed: List<Placed>?) = placed!!.map { it.from }
    private fun groupsOf(placed: List<Placed>?) = placed!!.map { it.group }

    @Test
    fun rowsShowAHeaderBeforeEachGroup() {
        val rows = TabGroups.rows(slots, groups, activeIndex = 0)
        assertEquals(
            listOf(
                TabRow.Tab(0, null),
                TabRow.Header("a", 1, 2), TabRow.Tab(1, "a"), TabRow.Tab(2, "a"),
                TabRow.Tab(3, null),
                TabRow.Header("b", 4, 2), TabRow.Tab(4, "b"), TabRow.Tab(5, "b"),
            ),
            rows,
        )
    }

    @Test
    fun collapsedGroupsHideTheirTabsExceptTheActiveOne() {
        val collapsed = listOf(TabGroup("a", "A", collapsed = true), TabGroup("b", "B", collapsed = true))
        val rows = TabGroups.rows(slots, collapsed, activeIndex = 5)
        assertEquals(
            listOf(TabRow.Tab(0, null), TabRow.Header("a", 1, 2), TabRow.Tab(3, null), TabRow.Header("b", 4, 2), TabRow.Tab(5, "b")),
            rows,
        )
    }

    @Test
    fun theMiddleOfATabMeansOntoAndItsEdgesMeanBetween() {
        val rows = listOf(TabRow.Tab(0, null), TabRow.Header("a", 1, 1), TabRow.Tab(1, "a"))
        val spans = listOf(0..40, 40..60, 60..100)
        assertEquals(TabDrop.Before(0), TabGroups.dropAt(rows, spans, 5))
        assertEquals(TabDrop.Onto(0), TabGroups.dropAt(rows, spans, 20))
        assertEquals(TabDrop.After(0), TabGroups.dropAt(rows, spans, 35))
        assertEquals(TabDrop.Before(1), TabGroups.dropAt(rows, spans, 45))
        assertEquals(TabDrop.IntoGroup("a"), TabGroups.dropAt(rows, spans, 55))
        assertEquals(TabDrop.End, TabGroups.dropAt(rows, spans, 500))
        assertEquals(TabDrop.End, TabGroups.dropAt(emptyList(), emptyList(), 10))
    }

    @Test
    fun droppingOntoAnUngroupedTabMakesANewGroup() {
        val placed = TabGroups.drop(slots, 5, TabDrop.Onto(3), "new")
        assertEquals(listOf(0, 1, 2, 3, 5, 4), order(placed))
        assertEquals(listOf(null, "a", "a", "new", "new", "b"), groupsOf(placed))
    }

    @Test
    fun droppingOntoAGroupedTabJoinsItsGroup() {
        val placed = TabGroups.drop(slots, 3, TabDrop.Onto(4), "new")
        assertEquals(listOf(0, 1, 2, 4, 3, 5), order(placed))
        assertEquals(listOf(null, "a", "a", "b", "b", "b"), groupsOf(placed))
    }

    @Test
    fun droppingBetweenTabsOfAGroupJoinsIt() {
        val placed = TabGroups.drop(slots, 3, TabDrop.Before(2), "new")
        assertEquals(listOf(0, 1, 3, 2, 4, 5), order(placed))
        assertEquals(listOf(null, "a", "a", "a", "b", "b"), groupsOf(placed))
    }

    @Test
    fun droppingOutsideAGroupLeavesIt() {
        val placed = TabGroups.drop(slots, 4, TabDrop.After(2), "new")
        assertEquals(listOf(0, 1, 2, 4, 3, 5), order(placed))
        assertEquals(listOf(null, "a", "a", null, null, "b"), groupsOf(placed))
        val toEnd = TabGroups.drop(slots, 1, TabDrop.End, "new")
        assertEquals(listOf(0, 2, 3, 4, 5, 1), order(toEnd))
        assertEquals(listOf(null, "a", null, "b", "b", null), groupsOf(toEnd))
    }

    @Test
    fun droppingOnAHeaderMakesTheTabTheGroupsFirst() {
        val placed = TabGroups.drop(slots, 3, TabDrop.IntoGroup("b"), "new")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), order(placed))
        assertEquals(listOf(null, "a", "a", "b", "b", "b"), groupsOf(placed))
    }

    @Test
    fun pinnedTabsStayFirstAndUngrouped() {
        // An ungrouped tab dropped above the pinned one stops right after it.
        assertEquals(listOf(0, 3, 1, 2, 4, 5), order(TabGroups.drop(slots, 3, TabDrop.Before(0), "new")))
        // A pinned tab never joins a group, even when dropped onto a grouped tab.
        val pinnedDrop = TabGroups.drop(listOf(TabSlot(true, null), TabSlot(true, null), TabSlot(false, "a")), 0, TabDrop.Onto(2), "new")
        assertEquals(listOf(1, 0, 2), order(pinnedDrop))
        assertEquals(listOf(null, null, "a"), groupsOf(pinnedDrop))
    }

    @Test
    fun droppingATabWhereItAlreadyIsChangesNothing() {
        assertNull(TabGroups.drop(slots, 3, TabDrop.Onto(3), "new"))
        assertNull(TabGroups.drop(slots, 3, TabDrop.After(2), "new"))
        assertNull(TabGroups.drop(slots, 4, TabDrop.IntoGroup("b"), "new"))
    }

    @Test
    fun removingATabFromTheMiddleOfAGroupMovesItAfterTheGroup() {
        val three = listOf(TabSlot(false, "a"), TabSlot(false, "a"), TabSlot(false, "a"), TabSlot(false, null))
        val placed = TabGroups.removeFromGroup(three, 0)
        assertEquals(listOf(1, 2, 0, 3), order(placed))
        assertEquals(listOf("a", "a", null, null), groupsOf(placed))
        assertEquals(listOf("a", "a", null, null), groupsOf(TabGroups.removeFromGroup(three, 2)))
    }

    @Test
    fun insertedTabsNeverSplitAGroup() {
        assertEquals("a", TabGroups.groupForInsert("a", "a", null))
        assertEquals("a", TabGroups.groupForInsert("a", null, "a"))
        assertEquals("a", TabGroups.groupForInsert(null, "a", "a"))
        assertNull(TabGroups.groupForInsert("a", "b", "c"))
        assertNull(TabGroups.groupForInsert(null, null, "a"))
    }

    @Test
    fun normalizeGathersGroupsAndDropsUnknownOnes() {
        val broken = listOf(
            TabSlot(true, "a"),
            TabSlot(false, "a"),
            TabSlot(false, null),
            TabSlot(false, "a"),
            TabSlot(false, "gone"),
        )
        val placed = TabGroups.normalize(broken, groups)
        assertEquals(listOf(0, 1, 3, 2, 4), order(placed))
        assertEquals(listOf(null, "a", "a", null, null), groupsOf(placed))
    }

    @Test
    fun pruneKeepsOnlyGroupsWithTabs() {
        assertEquals(listOf(TabGroup("b", "B")), TabGroups.prune(groups, listOf(TabSlot(false, "b"))))
    }
}
