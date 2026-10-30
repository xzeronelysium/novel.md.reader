package com.mdreader.ui

/** Estimates progress from the first visible block and its scrolled fraction. */
internal fun chapterScrollPercent(
    firstIndex: Int,
    firstOffset: Int,
    firstSize: Int,
    totalItems: Int,
    atEnd: Boolean,
): Int {
    if (totalItems <= 0) return 0
    if (atEnd) return 100
    val withinItem = if (firstSize > 0) {
        (-firstOffset.toLong()).toFloat().div(firstSize).coerceIn(0f, 1f)
    } else {
        0f
    }
    return (((firstIndex.coerceIn(0, totalItems - 1) + withinItem) / totalItems) * 100)
        .toInt().coerceIn(0, 99)
}
