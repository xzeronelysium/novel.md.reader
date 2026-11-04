package com.mdreader.md

/** Drops a leading Markdown heading when it only repeats the chapter's filename title. */
fun List<Block>.withoutOpeningTitle(title: String): List<Block> {
    val first = firstOrNull() as? Block.Heading ?: return this
    if (first.level > 2) return this

    val heading = normalizeHeading(first.text)
    val expected = normalizeHeading(title)
    val genericChapter = Regex("^chapter\\s+\\d+$").matches(heading)
    return if ((expected.isNotEmpty() && heading == expected) || genericChapter) {
        drop(1)
    } else {
        this
    }
}

private fun normalizeHeading(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
