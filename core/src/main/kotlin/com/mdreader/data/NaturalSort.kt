package com.mdreader.data

/**
 * Orders filenames the way a human reads them: runs of digits are compared as
 * numbers, so "chapter_2" sorts before "chapter_10".
 */
object NaturalSort : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0

        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]

            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val numberA = trimLeadingZeros(a.substring(i, endA))
                val numberB = trimLeadingZeros(b.substring(j, endB))

                val byLength = numberA.length - numberB.length
                if (byLength != 0) return byLength

                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue

                i = endA
                j = endB
                continue
            }

            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar

            i++
            j++
        }

        val byRemainingLength = (a.length - i) - (b.length - j)
        if (byRemainingLength != 0) return byRemainingLength

        // Identical ignoring case; fall back to a stable, case-sensitive order.
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun digitRunEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isAsciiDigit()) index++
        return index
    }

    private fun trimLeadingZeros(digits: String): String {
        val trimmed = digits.trimStart('0')
        return trimmed.ifEmpty { "0" }
    }
}
