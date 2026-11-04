package com.mdreader

import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import com.mdreader.data.WikiFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WikiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val project = com.mdreader.data.Project("p", "P", "t", emptyList(), 0)

    private fun bible() = RewriteStore(tmp.newFolder(), PlainBox).bible(project).also {
        it.merge(
            "## people/Raze Grimhold\n- also called: Raze, the young lord (Ch 1)\n- now: in the library (Ch 4)\n- now: leaving for Oden (Ch 48)\n" +
                "- voice: dry and guarded (Ch 2)\n- has a scarred hand (Ch 2)\n- hides a second soul (Ch 5, inferred)\n" +
                "## places/Dimensional Library\n- floats outside time (Ch 4)\n## synopsis\n- Ch 4: Raze finds the library"
        )
    }

    @Test fun pageHidesWhatLaterChaptersSaid() {
        val page = bible().page("people", "Raze Grimhold", 4)!!
        assertEquals("in the library", page.now!!.text)
        assertEquals(listOf("has a scarred hand"), page.known.map { it.text })
        assertEquals(emptyList<WikiFact>(), page.inferred)
        val later = bible().page("people", "Raze Grimhold", 48)!!
        assertEquals(listOf("hides a second soul"), later.inferred.map { it.text })
        assertEquals("leaving for Oden", later.now!!.text)
        assertEquals(1, later.history.size)
    }

    @Test fun entryIsUnknownBeforeItsFirstChapter() {
        val b = bible()
        assertNull(b.page("places", "Dimensional Library", 3))
        assertEquals(listOf("Raze Grimhold"), b.index(3).entries.map { it.name })
        assertEquals(2, b.index(4).entries.size)
        assertEquals(1, b.storySoFar(4).size)
        assertEquals(0, b.storySoFar(3).size)
    }

    @Test fun indexFindsNamesAndAliasesAsWholeWords() {
        val index = bible().index(48)
        val hits = index.find("The young lord met Raze at the Dimensional Library, not Razer.")
        assertEquals(listOf("Raze Grimhold", "Raze Grimhold", "Dimensional Library"), hits.map { it.third.name })
    }

    @Test fun nicknameLandsOnTheExistingSheet() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## people/Raze Grimhold\n- tall (Ch 1)")
        // A bare first name, a nickname the model claims, and the same name again.
        b.merge("## people/Raze\n- [personality] guarded (Ch 2)\n## people/The Young Lord\n- also called: Raze Grimhold (Ch 3)\n- [role] heir (Ch 3)")
        val people = b.index(10).entries.filter { it.kind == "people" }
        assertEquals(listOf("Raze Grimhold"), people.map { it.name })
        assertEquals(setOf("Raze", "The Young Lord"), people.single().aliases.toSet())
        val page = b.page("people", "Raze Grimhold", 10)!!
        assertEquals(listOf("guarded"), page.known("personality").map { it.text })
        assertEquals(listOf("heir"), page.known("role").map { it.text })
    }

    @Test fun longerNameAbsorbsAnEarlierShortSheet() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## people/Raze\n- tall (Ch 1)")
        b.merge("## people/Raze Grimhold\n- a baron's son (Ch 2)")
        val e = b.index(10).entries.filter { it.kind == "people" }
        assertEquals(listOf("Raze Grimhold"), e.map { it.name })
        assertEquals(listOf("Raze"), e.single().aliases)
        assertEquals(2, b.page("people", "Raze Grimhold", 10)!!.known.size)
    }

    @Test fun twoPeopleSharingAFirstNameStayApart() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## people/Lin Feng\n- a (Ch 1)\n## people/Lin Yue\n- b (Ch 1)")
        b.merge("## people/Lin\n- c (Ch 2)")
        assertEquals(listOf("Lin", "Lin Feng", "Lin Yue"), b.index(10).entries.map { it.name }.sorted())
    }

    @Test fun aliasNamingAnotherSheetIsDropped() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## people/The Hero\n- [role] chosen (Ch 1)\n## people/Peter\n- sad (Ch 1)")
        b.merge("## people/Peter\n- also called: The Hero, Young Master (Ch 2)")
        val e = b.index(5).entries.associateBy { it.name }
        assertEquals(listOf("Young Master"), e["Peter"]!!.aliases)
        assertEquals(true, e.containsKey("The Hero"))
    }

    @Test fun oneNameOnTwoShelvesIsOneEntry() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## places/Library\n- tall (Ch 1)\n## systems/Library\n- [rules] one book per visit (Ch 2)")
        assertEquals(1, b.index(5).entries.size)
        assertEquals(1, b.page("places", "Library", 5)!!.known("rules").size)
    }

    @Test fun figureDriftIsCaughtAndSpelledFiguresCount() {
        val source = "He had waited fifteen years. The debt was 300 gold."
        val memory = "- Raze is 16 (Ch 2)"
        val ok = com.mdreader.data.Fidelity.sceneProblems(source, "He had waited 15 years for the 300 gold.", emptyList(), 0, memory)
        assertEquals(emptyList<String>(), ok.filter { it.startsWith("These figures") || it.startsWith("These numbers") })
        val drift = com.mdreader.data.Fidelity.sceneProblems(source, "He had waited five years for the 300 gold, at 17.", emptyList(), 0, memory)
        assert(drift.any { it.startsWith("These figures") && it.contains("17") }) { drift.toString() }
        assert(drift.any { it.startsWith("These numbers") && it.contains("15") }) { drift.toString() }
    }

    @Test fun figuresLedgerIsKeptAndReachesTheWriter() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## figures\n- Raze is 16 years old (Ch 2)\n- the contract runs 15 years (Ch 28)")
        assert(b.context("anything").contains("15 years"))
    }

    @Test fun modelTagSynonymsLandInTheRightSection() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        b.merge("## people/Mara\n- [relationship] employs Tomas (Ch 1)\n- [skills] reads maps (Ch 1)\n- [history] born in the north (Ch 1)")
        val p = b.page("people", "Mara", 5)!!
        assertEquals(listOf("employs Tomas"), p.known("relation").map { it.text })
        assertEquals(listOf("reads maps"), p.known("ability").map { it.text })
        assertEquals(listOf("born in the north"), p.known("background").map { it.text })
    }

    @Test fun promptsAreGuidedAndSharedByBothSheetSteps() {
        val sheets = com.mdreader.data.RewritePrompts.SHEETS
        val record = com.mdreader.data.RewritePrompts.RECORD
        for (t in listOf(sheets, record)) {
            assert(t.contains("EXAMPLE") && t.contains("[relation]") && t.contains("STEP 2") && t.contains("## figures")) { t.take(300) }
            assert(!t.contains("null"))
        }
    }

    @Test fun dressedUpRepliesStillParse() {
        val b = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        val reply = "Sure, here are the sheets:\n```markdown\n### **people/Mara**\n* [role] captain (Ch 3)\n1. [ability] reads maps (Ch 3)\n```"
        assertEquals(true, b.usable(reply))
        assertEquals(2, b.merge(reply))
        assertEquals(false, b.usable("I could not find any facts in this chapter."))
        assertEquals(false, b.usable("## people/Mara\n- captain"))
    }

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }
}
