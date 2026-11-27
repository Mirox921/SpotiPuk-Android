package com.project.puk.player

import org.junit.Assert.*
import org.junit.Test

class QueueEditsTest {
    @Test fun removesAnOccurrenceWithoutRemovingItsDuplicates() {
        val edit = removeQueueEntry(listOf("a", "b", "a", "a"), 1, 2)!!
        assertEquals(listOf("a", "b", "a"), edit.items)
        assertEquals(1, edit.current)
    }

    @Test fun removingHistoryKeepsTheCurrentEntrySelected() {
        val edit = removeQueueEntry(listOf("a", "b", "c"), 1, 0)!!
        assertEquals(listOf("b", "c"), edit.items)
        assertEquals(0, edit.current)
        assertNull(removeQueueEntry(listOf("a", "b"), 1, 1))
        assertNull(removeQueueEntry(listOf("a"), 0, 3))
    }

    @Test fun movesOnlyTheSelectedUpcomingOccurrence() {
        val edit = moveQueueEntryNext(listOf("a", "b", "c", "b"), 0, 3)!!
        assertEquals(listOf("a", "b", "b", "c"), edit.items)
        assertEquals(0, edit.current)
        assertNull(moveQueueEntryNext(listOf("a", "b", "c"), 1, 0))
        assertNull(moveQueueEntryNext(listOf("a", "b", "c"), 1, 2))
    }

    @Test fun clearingUpcomingRetainsHistoryAndCurrent() {
        val edit = clearUpcomingEntries(listOf("a", "b", "c"), 1)!!
        assertEquals(listOf("a", "b"), edit.items)
        assertEquals(1, edit.current)
        assertNull(clearUpcomingEntries(emptyList<String>(), -1))
        assertNull(clearUpcomingEntries(listOf("a"), 0))
    }
    @Test fun reorderingTargetsOneOccurrenceAndRetainsCurrentAndHistory() {
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        val entries = listOf("history", "current", first, "next", second)
        val edit = reorderUpcomingEntries(entries, current = 1, from = 4, to = 2)!!
        org.junit.Assert.assertSame(second, edit.items[2])
        org.junit.Assert.assertSame(first, edit.items[3])
        org.junit.Assert.assertEquals(1, edit.current)
        org.junit.Assert.assertEquals(entries.take(2), edit.items.take(2))
        org.junit.Assert.assertNull(reorderUpcomingEntries(entries, current = 1, from = 4, to = 1))
    }
    @Test fun softTransitionGainReturnsToNormalAndIgnoresUnknownDuration() {
        org.junit.Assert.assertEquals(0f, trackTransitionGain(0, 10_000, fadeIn = true, fadeOut = true), 0f)
        org.junit.Assert.assertEquals(1f, trackTransitionGain(1_000, 10_000, fadeIn = true, fadeOut = true), 0f)
        org.junit.Assert.assertEquals(.5f, trackTransitionGain(9_625, 10_000, fadeIn = false, fadeOut = true), 0f)
        org.junit.Assert.assertEquals(1f, trackTransitionGain(2_000, -1, fadeIn = false, fadeOut = true), 0f)
    }
}
