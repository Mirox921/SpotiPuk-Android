package com.project.puk.player

/** Edit by occurrence index so identical URIs never remove or move the wrong entry. */
internal data class QueueEdit<T>(val items: List<T>, val current: Int)

internal fun <T> removeQueueEntry(items: List<T>, current: Int, selected: Int): QueueEdit<T>? {
    if (current !in items.indices || selected !in items.indices || selected == current) return null
    return QueueEdit(items.filterIndexed { index, _ -> index != selected }, if (selected < current) current - 1 else current)
}

internal fun <T> moveQueueEntryNext(items: List<T>, current: Int, selected: Int): QueueEdit<T>? {
    if (current !in items.indices || selected <= current + 1 || selected !in items.indices) return null
    val reordered = items.toMutableList()
    reordered.add(current + 1, reordered.removeAt(selected))
    return QueueEdit(reordered, current)
}

internal fun <T> clearUpcomingEntries(items: List<T>, current: Int): QueueEdit<T>? =
    if (current !in items.indices || current == items.lastIndex) null else QueueEdit(items.take(current + 1), current)


internal fun <T> reorderUpcomingEntries(items: List<T>, current: Int, from: Int, to: Int): QueueEdit<T>? {
    if (current !in items.indices || from !in items.indices || to !in items.indices ||
        from <= current || to <= current || from == to) return null
    val updated = items.toMutableList()
    updated.add(to, updated.removeAt(from))
    return QueueEdit(updated, current)
}
