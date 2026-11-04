package com.mdreader

import com.mdreader.data.Project
import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Replays recorded model replies (`$WIKI_EVAL/out/chNN.txt`) through the real merge, in order, then
 * writes `roster.txt` (what the next extraction is told) and `report.md` (every page as the reader
 * shows it). Skipped when WIKI_EVAL is unset.
 */
class WikiReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun replay() {
        val dir = System.getenv("WIKI_EVAL")?.let(::File)
        assumeTrue(dir != null)
        val bible = RewriteStore(tmp.newFolder(), object : SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String) = sealed
        }).bible(Project("p", "P", "t", emptyList(), 0))
        val outs = File(dir, "out").listFiles { f -> f.name.matches(Regex("ch\\d+\\.txt")) }.orEmpty().sortedBy { it.name }
        val log = StringBuilder()
        for (f in outs) log.append("${f.name}: +${bible.merge(f.readText())} bullets\n")
        File(dir, "roster.txt").writeText(bible.roster(100_000))
        val upTo = 999
        val report = StringBuilder(log).append("\n")
        for (e in bible.index(upTo).entries) {
            val p = bible.page(e.kind, e.name, upTo) ?: continue
            report.append("## ${e.name}  [${e.kind}]\n")
            if (p.aliases.isNotEmpty()) report.append("also: ${p.aliases.joinToString(", ")}\n")
            report.append("first seen: ch ${p.firstSeen}\n")
            p.now?.let { report.append("NOW (ch ${it.chapter}): ${it.text}\n") }
            p.history.forEach { report.append("EARLIER (ch ${it.chapter}): ${it.text}\n") }
            p.voice.forEach { report.append("VOICE: ${it.text}\n") }
            p.known.forEach { report.append("${(it.tag ?: "known").uppercase()} (ch ${it.chapter}): ${it.text}\n") }
            p.inferred.forEach { report.append("INFERRED (ch ${it.chapter}): ${it.text}\n") }
            report.append("\n")
        }
        File(dir, "report.md").writeText(report.toString())
    }
}
