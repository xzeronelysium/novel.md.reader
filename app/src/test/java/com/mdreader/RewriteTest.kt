package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.Project
import com.mdreader.data.Rewrite
import com.mdreader.data.Fidelity
import com.mdreader.data.RewriteClient
import com.mdreader.data.RewritePipeline
import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RewriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chapter = Chapter("Ch 1", "/novel/x/chapter-1", remote = true)
    private val project = Project("p-1", "X", "", listOf(chapter), 0L)

    @Test fun readsStreamedDeltas() {
        assertEquals("ab", RewriteClient.deltaOf("data: {\"choices\":[{\"delta\":{\"content\":\"ab\"}}]}"))
        assertNull(RewriteClient.deltaOf("data: [DONE]"))
        assertNull(RewriteClient.deltaOf(": keepalive"))
    }

    @Test fun flagsDroppedNumbers() {
        assertEquals(1, com.mdreader.data.Fidelity.check("He had 4500 coins and 3 swords", "He had many coins and 3 swords today").size)
        assertEquals(0, com.mdreader.data.Fidelity.check("It\u2019s 12", "It's 12").size)
    }

    @Test fun bibleKeepsOnlyCitedFactsAndFindsNamedSheets() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        val added = bible.merge("## people/Xiao Chen\n- scarred left hand (Ch 3)\n- loves tea\n## places/Burning Tree\n- roots glow red (Ch 5)")
        assertEquals(2, added)
        assertEquals(0, bible.merge("## people/Xiao Chen\n- scarred left hand (Ch 3)"))
        val ctx = bible.context("Xiao Chen walked on.")
        assert(ctx.contains("scarred left hand") && !ctx.contains("loves tea") && !ctx.contains("roots glow"))
    }

    @Test fun lengthHasALowFloorAndACeiling() {
        // A floor only against dropped content; the ceiling is the real limit.
        assertEquals(900 to 1450, RewriteClient.lengthTarget(1000))
    }

    @Test fun carryNoteDropsBookkeeping() {
        assertEquals("## Events\n- a", RewriteClient.carryNote("## Events\n- a\n\n## Enhancement plan\nx\n\n## Beat ledger\n1. y"))
    }

    @Test fun bibleDropsRewordedDuplicatesAndMatchesAliases() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        bible.merge("## people/Young Master Qian\n- also called: Qian, the slender young master (Ch 1)\n- arrogant, pays Mo Hua to ghostwrite his Formation assignments (Ch 1)\n- arrogant and pays Mo Hua to ghostwrite Formation assignments (Ch 2)")
        val text = tmpFile(bible)
        assertEquals(1, text.lines().count { it.contains("arrogant") })
        assert(bible.context("The slender young master flicked his fan.").contains("arrogant"))
    }

    private fun tmpFile(bible: com.mdreader.data.BibleStore): String = bible.context("Young Master Qian")

    @Test fun planIsReadFromTheAnalysis() {
        val paras = List(6) { "word ".repeat(120).trim() }
        val raw = "<think>hm</think>=== BEATS ===\n1. [P1-P2] Sunny kills the larva | cause: it attacked him\n2. [P5] He rests | cause: unknown\n" +
            "=== SCENES ===\nS1 | P1-P3 | type: action | register: grim | texture: a; b; c | sharpen: x | anchor: y\n" +
            "S2 | P4-P6 | type: inner | register: tense | texture: d | sharpen: z | anchor: w | wants: Sunny wants out; hides his fear | pace: quiet\n=== FLAGS ===\n- none"
        val plan = RewritePipeline.parsePlan(raw, paras)
        assertEquals(2, plan.beats.size)
        assertEquals("Sunny kills the larva", plan.beats[0].text)
        assertEquals("it attacked him", plan.beats[0].cause)
        assertEquals(listOf(1 to 3, 4 to 6), plan.scenes.map { it.from to it.to })
        assertEquals("action", plan.scenes[0].type)
        assertEquals("Sunny wants out; hides his fear", plan.scenes[1].wants)
        assertEquals("quiet", plan.scenes[1].pace)
        assertEquals("full", plan.scenes[0].pace)
        assertEquals(0, plan.flags.size)
    }

    @Test fun theDetailedSceneBriefAndVoicesAreRead() {
        val paras = List(4) { "word ".repeat(150).trim() }
        val raw = "=== SCENES ===\nS1 | P1-P4 | type: dialogue | turn: Ryan trusts Lumian | dialogue: P2 Ryan apologises, stiff and formal; P3 Lumian teases | cut: P4 repeats the shepherd fact | end: Ryan nods at the door\n" +
            "=== FLAGS ===\n- none\n=== KEEP ===\n- none\n=== VOICES ===\n- Lumian: cocky village prankster, quick and teasing\n- Ryan: stiff, polite foreigner\n- none"
        val plan = RewritePipeline.parsePlan(raw, paras)
        val scene = plan.scenes.single()
        assertEquals("Ryan trusts Lumian", scene.turn)
        assertEquals("P2 Ryan apologises, stiff and formal; P3 Lumian teases", scene.dialogue)
        assertEquals("P4 repeats the shepherd fact", scene.cut)
        assertEquals("Ryan nods at the door", scene.end)
        assertEquals(mapOf("Lumian" to "cocky village prankster, quick and teasing", "Ryan" to "stiff, polite foreigner"), plan.voices)
        // Only the people in a scene's source get their voice note.
        assertEquals(listOf("Ryan: stiff, polite foreigner"), RewritePipeline.voicesFor(plan, "Ryan bowed."))
    }

    @Test fun theNaturalVersionOfAStiffLineTravelsInTheBrief() {
        val raw = "=== SCENES ===\nS1 | P1-P2 | type: dialogue | dialogue: P1 Wen: refuses, no silver => It's a lot of silver, and I don't have it; P2 boy: asks for half => Half, then? | end: the boy bows\n"
        val scene = RewritePipeline.parsePlan(raw, listOf("a", "b")).scenes.single()
        assertEquals("P1 Wen: refuses, no silver => It's a lot of silver, and I don't have it; P2 boy: asks for half => Half, then?", scene.dialogue)
        assertEquals("the boy bows", scene.end)
    }

    @Test fun smallSlipsInSceneRangesAreMendedAndBigOnesExplained() {
        val s = { from: Int, to: Int -> RewritePipeline.Scene(from, to, turn = "t$from") }
        // An overlap, a gap and a last scene that stops one short: the briefs survive.
        val mended = RewritePipeline.snapScenes(listOf(s(1, 10), s(9, 20), s(23, 29)), 30)!!
        assertEquals(listOf(1 to 10, 11 to 20, 21 to 30), mended.map { it.from to it.to })
        assertEquals(listOf("t1", "t9", "t23"), mended.map { it.turn })
        // A scene fully inside the one before it is dropped.
        assertEquals(2, RewritePipeline.snapScenes(listOf(s(1, 10), s(5, 8), s(11, 20)), 20)!!.size)
        // Half the chapter missing: not mended.
        assertNull(RewritePipeline.snapScenes(listOf(s(1, 10), s(40, 50)), 50))
        assertNull(RewritePipeline.snapScenes(listOf(s(1, 10)), 50))
        assertNull(RewritePipeline.snapScenes(emptyList(), 5))
        val why = RewritePipeline.sceneProblem(listOf(s(1, 10), s(40, 50)), 50, "=== SCENES ===", true)
        assert(why.contains("P10") && why.contains("P40") && why.contains("VOICES")) { why }
        assert(RewritePipeline.sceneProblem(emptyList(), 5, "no headings", false).contains("cut off"))
    }

    @Test fun aNearlyRightPlanKeepsItsBriefs() {
        val raw = "=== SCENES ===\nS1 | P1-P3 | type: dialogue | turn: a | end: x\nS2 | P5-P5 | type: action | turn: b | end: y\n=== VOICES ===\n- Wen: blunt\n"
        val plan = RewritePipeline.parsePlan(raw, List(6) { "word ".repeat(200).trim() })
        assertNull(plan.problem)
        assertEquals(listOf("a", "b"), plan.scenes.map { it.turn })
        assertEquals(6, plan.scenes.last().to)
    }

    @Test fun brokenScenesFallBackToChunks() {
        val paras = List(10) { "word ".repeat(200).trim() }
        val raw = "=== SCENES ===\nS1 | P1-P4 | type: action\nS2 | P7-P10 | type: inner"
        val scenes = RewritePipeline.parsePlan(raw, paras).scenes
        assertEquals(1, scenes.first().from)
        assertEquals(10, scenes.last().to)
        assert(scenes.zipWithNext().all { (a, b) -> b.from == a.to + 1 })
    }

    @Test fun protectedPhrasesMustActuallyBeQuotedInTheSource() {
        val paras = listOf("She said, “Au revoir, my cabbages!” She left through the side door.")
        val raw = """
            === BEATS ===
            1. [P1] She says goodbye and leaves | cause: unknown
            === SCENES ===
            S1 | P1-P1 | type: dialogue
            === FLAGS ===
            - her farewell is deliberately playful
            === KEEP ===
            - “Au revoir, my cabbages!”
            - Goodbye, my cabbages!
            - She left through the side door.
            - revoir, my cabbage
            - none
        """.trimIndent()
        val plan = RewritePipeline.parsePlan(raw, paras)
        assertEquals(listOf("Au revoir, my cabbages!"), plan.keep)
        assertEquals(listOf("her farewell is deliberately playful"), plan.flags)
        assertEquals(1, plan.beats.size)
        assertEquals(emptyList<String>(), RewritePipeline.parsePlan("=== FLAGS ===\n- none", paras).keep)
    }

    @Test fun protectedWordingFlagsChangedWordsWithoutForcingThoughtsIntoSpeech() {
        val phrase = "Au revoir, my cabbages!"
        val source = "“$phrase” she said."
        fun protectedProblems(draft: String) = Fidelity.sceneProblems(source, draft, emptyList(), 0, "", keep = listOf(phrase))
            .filter { it.contains("protected phrases") }
        assertEquals(emptyList<String>(), protectedProblems("She waved. \"$phrase\""))
        // The planner can mistake a quoted thought or label for speech. A wording check must not
        // force that material into a character's mouth just because formatting changed.
        assertEquals(emptyList<String>(), protectedProblems("She thought: *$phrase*"))
        assertEquals(1, protectedProblems("“Goodbye, my cabbages!”").size)
        assertEquals(1, protectedProblems("“Au revoir, my cabbages?”").size)
    }

    @Test fun protectedPhraseMatchingAllowsTypographyButNotPartialWords() {
        assertEquals(true, Fidelity.containsQuotedWording("‘Don’t give up!’ she said.", "Don't give up!"))
        assertEquals(true, Fidelity.containsQuotedWording("'Don't give up!' she said.", "Don’t give up!"))
        assertEquals(true, Fidelity.containsQuotedWording("“Please, never   say never.”", "never say never"))
        assertEquals(false, Fidelity.containsQuotedWording("“Stay alive.”", "Stay a"))
        assertEquals(false, Fidelity.containsQuotedWording("“Don't surrender!”", ""))
    }

    @Test fun ordinaryDialogueCanImproveWithoutAnExactTextOrParagraphCountGate() {
        val source = "“It is not possible for me to agree.”\n\n“Do not ask again,” she said.\n\n“I have made my decision.”"
        val draft = "“I can't agree. Don't ask again. I've made up my mind,” she said."
        assertEquals(emptyList<String>(), Fidelity.sceneProblems(source, draft, emptyList(), 0, ""))
    }

    @Test fun tinyScenesAreFoldedAndHugeOnesSplit() {
        val paras = listOf("word ".repeat(50).trim()) + List(8) { "word ".repeat(300).trim() }
        val raw = "=== SCENES ===\nS1 | P1-P1 | type: comedy\nS2 | P2-P9 | type: crowd"
        val scenes = RewritePipeline.parsePlan(raw, paras).scenes
        assertEquals(1, scenes.first().from)
        assert(scenes.size > 2)
        assert(scenes.all { it.type == "comedy" || it.type == "crowd" })
    }

    @Test fun cleanTurnsNoticesIntoOnePanel() {
        val raw = "He froze.\n\n[Ding! Conditions met.]\n\n> **[Binding System activated.]**\n\nHe smiled."
        assertEquals(
            "He froze.\n\n> **[Ding! Conditions met.]**\n> **[Binding System activated.]**\n\nHe smiled.",
            RewritePipeline.clean(raw),
        )
    }

    @Test fun cleanGivesEveryKindItsForm() {
        val raw = listOf(
            "He opened the panel.",
            "",
            "[Skill Name: Invisibility",
            "Cost: 8000 skill points]",
            "",
            "Name: Yuan",
            "",
            "Cultivation: First Level Spirit Apprentice",
            "",
            "Physique: Heaven Refining Physique",
            "",
            "Fang Yun: “Heh, you figured it out.”",
            "",
            "Wang Ping: \"Why do you say that?\"",
            "",
            "Note: this line is prose, she said.",
        ).joinToString("\n")
        assertEquals(
            listOf(
                "He opened the panel.",
                "",
                "> **[Skill Name: Invisibility**",
                "> **Cost: 8000 skill points]**",
                "> **Name: Yuan**",
                "> **Cultivation: First Level Spirit Apprentice**",
                "> **Physique: Heaven Refining Physique**",
                "> **Fang Yun:** “Heh, you figured it out.”",
                "> **Wang Ping:** \"Why do you say that?\"",
                "",
                "Note: this line is prose, she said.",
            ).joinToString("\n"),
            RewritePipeline.clean(raw),
        )
    }

    @Test fun anUnclosedBracketStaysProse() {
        assertEquals("[He never closed this\n\nand went on.", RewritePipeline.clean("[He never closed this\n\nand went on."))
    }

    @Test fun cleanStripsWrappers() {
        assertEquals("Body text", RewritePipeline.clean("<think>x</think>```\n# Title\nBody text\n```"))
    }

    @Test fun scenePassesWhenFaithful() {
        val source = "Sunny killed the larva with 3 blows. He rested for 3 seconds."
        val draft = "Sunny killed the larva with 3 blows, each one heavier than the last. ".repeat(3) + "He rested for 3 seconds, breathing hard."
        assertEquals(emptyList<String>(), Fidelity.sceneProblems(source, draft, listOf("Sunny killed the larva with three blows"), 20, ""))
    }

    @Test fun sceneProblemsNameWhatToFix() {
        val source = "Sunny killed the larva with 3 blows. The older soldier watched."
        val draft = "The older soldier watched. The older soldier frowned. The older soldier left. An enigmatic Shadow Pact formed. He was a ten-year-old."
        val problems = Fidelity.sceneProblems(source, draft, listOf("Sunny killed the larva quickly"), 60, "")
        val text = problems.joinToString("\n")
        assert(text.contains("under the source")) { text }
        assert(text.contains("beats are missing")) { text }
        assert(text.contains("numbers")) { text }
        assert(text.contains("enigmatic")) { text }
        assert(text.contains("older soldier")) { text }
        assert(text.contains("Shadow")) { text }
        assert(text.contains("ten-year-old")) { text }
    }

    @Test fun nameCheckIgnoresContractionsDialogueAndCommonNouns() {
        val source = "The sect elder sneered at Dariège's monk. She said nothing."
        val draft = "“Save us,” she said, and the Sect elder in Dariège laughed. Don’t, he thought, and Aren’t you tired. He took the “Social Parasite” route with Zhou."
        assertEquals(listOf("Zhou", "Social Parasite"), Fidelity.inventedNames(draft, source))
    }

    @Test fun repeatedNamesAreNotDescriptions() {
        val draft = "the Mountain King roared. the Mountain King fell. the Mountain King died. the shifty slave ran. the shifty slave hid. the shifty slave won."
        assertEquals(listOf("the shifty slave"), Fidelity.repeatedDescriptions("", draft))
    }

    @Test fun repetitionAcrossScenesCounts() {
        val earlier = "the shifty slave ran. the shifty slave hid."
        assertEquals(listOf("the shifty slave"), Fidelity.repeatedDescriptions("", "the shifty slave won.", earlier))
        assertEquals(emptyList<String>(), Fidelity.repeatedDescriptions("", "Sunny won.", earlier))
    }

    @Test fun footnoteNumbersAreNotStory() {
        val source = "A thousand li.\\[3\\]\n\n3\\. ”Li” is a unit. One li is about 500 meters. ☜"
        assertEquals(emptyList<String>(), Fidelity.sceneProblems(source, "A thousand li.", emptyList(), 0, ""))
    }

    @Test fun memoryMatchesWholeNamesOnly() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        bible.merge("## people/Lin\n- a swordsman (Ch 1)")
        assertEquals(false, bible.context("He drew a line in the sand.").contains("swordsman"))
        assertEquals(true, bible.context("Lin drew his sword.").contains("swordsman"))
    }

    @Test fun memoryKeepsNewestStateAndStorySoFar() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        bible.merge("## people/Sunny\n- now: dormant, in the mountains (Ch 4)\n## synopsis\n- Ch 4: Sunny fights the larva.")
        bible.merge("## people/Sunny\n- now: dormant, in the mountains, injured (Ch 5)\n## systems/Ranks\n- Awakened rank above Dormant (Ch 5)\n## synopsis\n- Ch 5: Sunny kills the larva.")
        val ctx = bible.context("Sunny climbed. Ranks mattered.")
        assert(ctx.contains("injured") && !ctx.contains("dormant, in the mountains (Ch 4)")) { ctx }
        assert(ctx.contains("[story so far]") && ctx.contains("Ch 5: Sunny kills the larva.")) { ctx }
        assert(ctx.contains("Awakened rank")) { ctx }
    }

    @Test fun mapsFailuresToShortMessages() {
        assertEquals("Rate limited. Try again in 7 s.", RewriteClient.failure(429, "7", "{}"))
        assertEquals("The key was refused. Check it in the settings.", RewriteClient.failure(401, null, "secret"))
        assertEquals("The service answered 418.", RewriteClient.failure(418, null, "<!doctype html><title>418</title>"))
    }

    @Test fun storeRoundTripsAndDeletes() {
        val store = RewriteStore(tmp.newFolder(), PlainBox)
        assertNull(store.read(project, chapter))
        store.write(project, chapter, Rewrite("# T\nbody", "- n"))
        assertEquals(Rewrite("# T\nbody", "- n"), store.read(project, chapter))
        store.deleteAll(project)
        assertNull(store.read(project, chapter))
    }

    @Test fun keyIsSealedOnDisk() {
        val dir = tmp.newFolder()
        val store = RewriteStore(dir, PlainBox)
        store.saveSettings(com.mdreader.data.RewriteSettings(apiKey = "sk-secret", model = "m", memoryDepth = 0))
        assertEquals(false, java.io.File(dir, "rewrite-settings.json").readText().contains("sk-secret"))
        assertEquals("sk-secret", store.settings().apiKey)
        assertEquals(0, store.settings().memoryDepth)
    }

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }
}
