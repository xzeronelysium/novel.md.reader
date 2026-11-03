package com.mdreader.data

/**
 * The rewrite, as one fixed script of three steps. No model decides what happens next:
 *
 * 1. **Analyse** the source into beats (with causes), scenes (with a plan each) and flags.
 * 2. **Write** each scene on its own, so every scene gets the room it needs. Code checks each one
 *    (length, beats, numbers, banned words, repeated descriptions, invented names) and gives a
 *    failing scene one repair with the exact reasons. The better draft is kept.
 * 3. **Record** what the chapter establishes into the note and the memory sheets.
 *
 * A failed analysis or record never fails the rewrite: scenes then fall back to fixed-size
 * chunks, and the note to what code alone can say.
 */
object RewritePipeline {

    data class Beat(val from: Int, val to: Int, val text: String, val cause: String)

    data class Scene(
        val from: Int,
        val to: Int,
        val type: String = "inner",
        val register: String = "",
        val texture: String = "",
        val sharpen: String = "",
        val anchor: String = "",
        /** What each character is after, and what goes unsaid. */
        val wants: String = "",
        /** quiet, building, loud; or "full" when the plan does not say. */
        val pace: String = "full",
        /** What is different by the end of the scene: its point. */
        val turn: String = "",
        /** The lines to recompose, each with its speaker's intent in plain words. */
        val dialogue: String = "",
        /** Repetition in the source that can go without losing a beat. */
        val cut: String = "",
        /** The image, action or line the scene lands on. */
        val end: String = "",
    )

    /** [keep]: spoken phrases whose exact wording matters, such as catchphrases and wordplay. */
    /** [voices]: how each speaker of the chapter talks, by name, for the scenes they appear in. */
    data class Plan(
        val beats: List<Beat>,
        val scenes: List<Scene>,
        val flags: List<String>,
        val keep: List<String> = emptyList(),
        val voices: Map<String, String> = emptyMap(),
        /** Why the scenes of the analysis could not be used (then they are plain chunks), or null. */
        val problem: String? = null,
    )

    /** The voice notes of the people a scene's source mentions by name. */
    internal fun voicesFor(plan: Plan, source: String): List<String> =
        plan.voices.filterKeys { name -> Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(source) }
            .map { (name, voice) -> "$name: $voice" }

    /** Source paragraphs, as the analysis numbers them. */
    fun paragraphs(markdown: String): List<String> =
        markdown.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun rewrite(
        settings: RewriteSettings,
        number: Int,
        title: String,
        markdown: String,
        previousNote: String? = null,
        sheets: String = "",
        previousTail: String? = null,
        onStage: (String?) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Rewrite {
        val paras = paragraphs(markdown)
        val memory = buildString {
            if (sheets.isNotBlank()) append("MEMORY (canon from earlier chapters):\n").append(sheets.trim()).append("\n\n")
            if (!previousNote.isNullOrBlank()) append("NOTE OF THE PREVIOUS CHAPTER:\n").append(previousNote.trim()).append("\n\n")
        }

        // 1. Analyse.
        onStage("ANALYSING")
        val numbered = paras.mapIndexed { i, p -> "[P${i + 1}] $p" }.joinToString("\n\n")
        val warnings = mutableListOf<String>()
        val analysisUser = memory + "SOURCE CHAPTER: $title\n\n$numbered"
        var plan = runCatching {
            parsePlan(RewriteClient.stream(settings, RewritePrompts.ANALYSE, analysisUser, onProgress), paras)
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            fallbackPlan(paras)
        }
        var analyses = 1
        val planProblem = plan.problem
        if (planProblem != null) {
            onStage("ANALYSING AGAIN")
            analyses++
            warnings += "plan: the scenes of the analysis were unusable ($planProblem)"
            val retry = runCatching {
                parsePlan(
                    RewriteClient.stream(
                        settings, RewritePrompts.ANALYSE,
                        analysisUser + "\n\n---\n\nYOUR PREVIOUS REPLY COULD NOT BE USED: $planProblem. Reply again in exactly the form given. The scenes must cover P1 to P${paras.size} once each, in order, with no gap and no overlap, and the VOICES section must follow the scenes.",
                        onProgress,
                    ),
                    paras,
                )
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            if (retry != null && retry.problem == null) {
                plan = retry
                warnings += "plan: the second analysis was usable"
            } else {
                warnings += "plan: the second analysis was no better; scenes were cut by length and carry no brief"
            }
        }
        if (plan.beats.isEmpty()) warnings += "the planning step gave no beats; scenes were cut by length and beats were not checked"

        // 2. Write, scene by scene.
        val known = sheets + "\n" + previousNote.orEmpty() + "\n" + previousTail.orEmpty() + "\n" + title
        val written = mutableListOf<String>()
        var repairs = 0
        val repairLog = mutableListOf<String>()
        for ((i, scene) in plan.scenes.withIndex()) {
            val label = "SCENE ${i + 1}/${plan.scenes.size}"
            onStage("WRITING $label")
            val source = paras.subList(scene.from - 1, scene.to).joinToString("\n\n")
            val beats = plan.beats.filter { it.from in scene.from..scene.to }
            val keep = plan.keep.filter { Fidelity.containsQuotedWording(source, it) }
            val (minWords, maxWords) = RewriteClient.lengthTarget(Fidelity.wordCount(source))
            val before = if (i == 0) previousTail?.let { "END OF THE PREVIOUS CHAPTER (pick up from it, do not repeat it):\n$it" }
            else "THE SCENE JUST BEFORE ENDS (continue from it, do not repeat it):\n" + RewriteClient.tailWords(written.last(), 150)
            val user = buildString {
                append(memory)
                if (before != null) append(before).append("\n\n")
                append("BRIEF FOR THIS SCENE\n")
                listOf(
                    "type" to scene.type, "register" to scene.register, "pace" to scene.pace, "turn" to scene.turn,
                    "wants" to scene.wants, "dialogue" to scene.dialogue, "sharpen" to scene.sharpen,
                    "texture" to scene.texture, "anchor" to scene.anchor, "cut" to scene.cut, "end" to scene.end,
                ).filter { it.second.isNotBlank() }.forEach { (k, v) -> append("- $k: $v\n") }
                append("\n")
                val voices = voicesFor(plan, source)
                if (voices.isNotEmpty()) append("VOICES (write each person's lines in this voice):\n").append(voices.joinToString("\n") { "- $it" }).append("\n\n")
                if (beats.isNotEmpty()) append("BEATS THAT MUST HAPPEN, IN ORDER:\n").append(beats.joinToString("\n") { "- ${it.text} (cause: ${it.cause})" }).append("\n\n")
                if (keep.isNotEmpty()) append("PROTECTED PHRASES (retain wording in the source's original context and role; other wording may improve. If the plan selected a thought or label, keep it as such, never force it into speech):\n").append(keep.joinToString("\n") { "- $it" }).append("\n\n")
                if (i > 0) append("If this scene opens at a new time or place compared with the scene before, start it with a line containing only ---.\n\n")
                if (i == plan.scenes.lastIndex) {
                    append("THIS IS THE LAST SCENE OF THE CHAPTER. End on the same moment, line and feeling as the source's last paragraph; add nothing after it.\n\n")
                }
                append("LENGTH: the source scene has ${Fidelity.wordCount(source)} words. Aim for about $minWords to $maxWords words. A scene far under $minWords has lost beats or exchanges; cut only the repetition the brief marks, never an event, a fact or a line of dialogue that matters. A quiet scene may stay close to the source's length.\n\n")
                append("SOURCE SCENE (Ch $number, $title):\n\n").append(source)
            }
            val system = RewritePrompts.WRITE + "\n\n" + (RewritePrompts.METHODS[scene.type] ?: "")

            var draft = clean(RewriteClient.stream(settings, system, user, onProgress, RewriteClient.CREATIVE))
            val previousScene = written.lastOrNull().orEmpty()
            val soFar = written.joinToString("\n\n")
            var problems = Fidelity.sceneProblems(source, draft, beats.map { it.text }, minWords, known, previousScene, soFar, keep)
            if (repairWorthwhile(problems, draft, minWords)) {
                onStage("REPAIRING $label")
                repairs++
                val firstKinds = problems.map(::problemKind)
                var outcome = "second draft failed"
                val retry = user + "\n\n---\n\nYOUR DRAFT OF THIS SCENE WAS REJECTED FOR THESE REASONS:\n" +
                    problems.joinToString("\n") { "- $it" } + "\n\nYOUR DRAFT:\n" + draft +
                    "\n\nWrite the whole scene again, fixing exactly these problems and keeping what was good."
                val second = runCatching { clean(RewriteClient.stream(settings, system, retry, onProgress, RewriteClient.CREATIVE)) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                if (second != null && second.isNotBlank()) {
                    val secondProblems = Fidelity.sceneProblems(source, second, beats.map { it.text }, minWords, known, previousScene, soFar, keep)
                    outcome = "${problems.size} -> ${secondProblems.size} problems, "
                    if (secondProblems.size < problems.size ||
                        (secondProblems.size == problems.size && Fidelity.wordCount(second) > Fidelity.wordCount(draft))
                    ) {
                        draft = second
                        problems = secondProblems
                        outcome += "kept the repair"
                    } else {
                        outcome += "kept the first draft"
                    }
                }
                repairLog += "scene ${i + 1}: ${firstKinds.joinToString("+")}: $outcome"
            }
            if (draft.isBlank()) throw java.io.IOException("The service returned an empty scene.")
            written += draft
            problems.forEach { warnings += "scene ${i + 1}: $it" }
        }
        val chapter = "# $title\n\n" + written.joinToString("\n\n")

        // 3. Record.
        onStage("RECORDING")
        val recorded = runCatching {
            RewriteClient.stream(
                settings, RewritePrompts.RECORD,
                memory + "Chapter citation: (Ch $number)\n\nSOURCE CHAPTER: $title\n\n$markdown", onProgress,
            )
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull().orEmpty()
        val noteBody = recorded.substringAfter(RewritePrompts.NOTE, "").substringBefore(RewritePrompts.SHEETS_MARK).trim()
        val sheetUpdates = recorded.substringAfter(RewritePrompts.SHEETS_MARK, "").trim()
        if (recorded.isBlank()) warnings += "the memory step failed; no sheet updates for this chapter"

        warnings += Fidelity.check(markdown, chapter)
        val note = buildString {
            append("## Events\n")
            append(plan.beats.joinToString("\n") { "- ${it.text}" + if (it.cause.isNotBlank() && !it.cause.equals("unknown", true)) " — because ${it.cause}" else "" })
            if (noteBody.isNotEmpty()) append("\n\n").append(noteBody)
            append("\n## Voice flags and conflicts\n").append(plan.flags.ifEmpty { listOf("none") }.joinToString("\n") { "- $it" })
            append("\n\n## Enhancement plan\n")
            append(plan.scenes.withIndex().joinToString("\n") { (i, s) -> "S${i + 1} | P${s.from}-P${s.to} | ${s.type} | ${s.register} | ${s.texture} | ${s.sharpen} | ${s.anchor} | ${s.wants} | ${s.pace} | ${s.turn} | ${s.dialogue} | ${s.cut} | ${s.end}" })
            if (plan.voices.isNotEmpty()) append("\n\n## Voices\n").append(plan.voices.entries.joinToString("\n") { "- ${it.key}: ${it.value}" })
            if (plan.keep.isNotEmpty()) append("\n\n## Protected dialogue\n").append(plan.keep.joinToString("\n") { "- $it" })
            if (warnings.isNotEmpty()) append("\n\n## Fidelity warnings\n").append(warnings.joinToString("\n") { "- $it" })
            append("\n\n## Run\n- prompt ${RewriteClient.PROMPT_VERSION}\n- model ${settings.model}")
            if (settings.effort.isNotBlank()) append(" (effort ${settings.effort})")
            append("\n- scenes ${plan.scenes.size}, repairs $repairs")
            append("\n- requests ${analyses + plan.scenes.size + repairs + 1} (analysis $analyses, scenes ${plan.scenes.size}, repairs $repairs, record 1)")
            if (repairLog.isNotEmpty()) append("\n\n## Repairs\n").append(repairLog.joinToString("\n") { "- $it" })
        }
        return Rewrite(chapter, note, sheetUpdates)
    }

    /** Reads the analysis; scenes that don't cover every paragraph once, in order, are replaced. */
    internal fun parsePlan(raw: String, paras: List<String>): Plan {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val n = paras.size
        fun section(start: String, vararg ends: String): String {
            val at = text.indexOf(start)
            if (at < 0) return ""
            val from = at + start.length
            val to = ends.map { text.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: text.length
            return text.substring(from, to)
        }
        val range = Regex("""\[?P(\d+)(?:\s*[-–]\s*P?(\d+))?\]?""")

        val beats = section(RewritePrompts.BEATS, RewritePrompts.SCENES, RewritePrompts.FLAGS, RewritePrompts.KEEP, RewritePrompts.VOICES).lines().mapNotNull { line ->
            val body = Regex("""^\s*\d+[.)]\s*(.+)""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val r = range.find(body)
            val from = r?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, maxOf(n, 1)) ?: return@mapNotNull null
            val to = r.groupValues[2].toIntOrNull()?.coerceIn(from, maxOf(n, 1)) ?: from
            val rest = body.substring(r.range.last + 1).trim()
            Beat(from, to, rest.substringBefore("| cause:").substringBefore("|").trim(), rest.substringAfter("cause:", "").trim())
        }

        val scenes = section(RewritePrompts.SCENES, RewritePrompts.FLAGS, RewritePrompts.KEEP, RewritePrompts.VOICES).lines().mapNotNull { line ->
            if (!line.trim().startsWith("S", ignoreCase = true)) return@mapNotNull null
            val parts = line.split('|').map { it.trim() }
            val r = parts.firstNotNullOfOrNull { range.find(it)?.takeIf { m -> it.startsWith("P") || it.startsWith("[P") } } ?: return@mapNotNull null
            val from = r.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val to = r.groupValues[2].toIntOrNull() ?: from
            fun field(name: String) = parts.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
            Scene(from, to, field("type").lowercase().takeIf { it in RewritePrompts.METHODS } ?: "inner",
                field("register"), field("texture"), field("sharpen"), field("anchor"), field("wants"),
                field("pace").lowercase().takeIf { it in setOf("quiet", "building", "loud") } ?: "full",
                field("turn"), field("dialogue"), field("cut"), field("end"))
        }

        val flags = section(RewritePrompts.FLAGS, RewritePrompts.KEEP, RewritePrompts.VOICES).lines().map { it.trim() }
            .filter { it.startsWith("- ") && !it.removePrefix("- ").trim().equals("none", true) }
            .map { it.removePrefix("- ").trim() }
        // Only lines the source really prints: a "keep" line the model misquoted would be impossible to meet.
        val sourceText = paras.joinToString("\n\n")
        val voices = section(RewritePrompts.VOICES).lines().map { it.trim().removePrefix("- ").trim() }
            .mapNotNull { line ->
                val name = line.substringBefore(':', "").trim()
                val voice = line.substringAfter(':', "").trim()
                if (name.isEmpty() || voice.isEmpty() || name.split(' ').size > 4) null else name to voice
            }.toMap()
        val keep = section(RewritePrompts.KEEP, RewritePrompts.VOICES).lines().map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().removeSurrounding("\"").removeSurrounding("“", "”")
                .removeSurrounding("'").removeSurrounding("‘", "’").trim() }
            .filter { it.isNotEmpty() && !it.equals("none", true) && Fidelity.containsQuotedWording(sourceText, it) }
            .distinct()

        val snapped = snapScenes(scenes, n)
        val problem = if (snapped != null) null else sceneProblem(scenes, n, text, voices.isEmpty())
        return Plan(beats, if (snapped != null) balance(snapped, paras) else fallbackPlan(paras).scenes, flags, keep, voices, problem)
    }

    /**
     * Scenes that cover every paragraph once, in order. Small slips in the model's ranges (a gap or an
     * overlap of up to [SLIP] paragraphs, an end a few short of the last paragraph) are mended so the
     * briefs survive; anything bigger returns null and the scenes are replaced.
     */
    internal fun snapScenes(scenes: List<Scene>, n: Int): List<Scene>? {
        if (scenes.isEmpty() || n < 1) return null
        if (scenes.any { it.from > it.to || it.from < 1 || it.to > n }) return null
        if (scenes.first().from > SLIP + 1 || n - scenes.last().to > SLIP) return null
        val out = mutableListOf<Scene>()
        for (s in scenes) {
            val from = if (out.isEmpty()) 1 else out.last().to + 1
            if (s.to < from) continue // swallowed by an overlap
            if (out.isNotEmpty() && Math.abs(s.from - from) > SLIP) return null
            out += s.copy(from = from)
        }
        if (out.isEmpty()) return null
        out[out.lastIndex] = out.last().copy(to = n)
        return out
    }

    private const val SLIP = 3

    /**
     * Whether a second request can fix what the checks found. Measured on real runs: a repair for
     * a missing beat, a short scene or a small number never fixed it (the beat check also has false
     * alarms), while banned phrases, invented names and lost protected lines are fixed nearly
     * every time. The rest stays in the note as a warning.
     */
    internal fun repairWorthwhile(problems: List<String>, draft: String, minWords: Int): Boolean {
        if (problems.isEmpty()) return false
        if (minWords > 0 && Fidelity.wordCount(draft) < minWords * 0.75) return true
        return problems.any { message ->
            when (problemKind(message)) {
                "banned", "names", "protected" -> true
                "beats" -> message.substringAfter(": ").split("; ").size >= 3
                "numbers" -> Regex("\\d+").findAll(message.substringAfter(": ")).any { (it.value.toLongOrNull() ?: 0L) > 10L }
                else -> false
            }
        }
    }

    private val ageWords = listOf("two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty").withIndex().associate { (i, w) -> w to i + 2 }

    /** "ten-year-old" is written as "10-year-old", so the age rule never costs a request. */
    internal fun digitAges(text: String): String =
        Regex("\\b(" + ageWords.keys.joinToString("|") + ")-year-old", RegexOption.IGNORE_CASE)
            .replace(text) { ageWords[it.groupValues[1].lowercase()].toString() + "-year-old" }

    /** A short name for what a scene problem is about, for the repair log. */
    internal fun problemKind(message: String): String = when {
        message.startsWith("It has") -> "length"
        message.startsWith("These beats") -> "beats"
        message.startsWith("These numbers") -> "numbers"
        message.startsWith("These banned") -> "banned"
        message.startsWith("These descriptions") -> "repeats"
        message.startsWith("These names") -> "names"
        message.startsWith("Restore these protected") -> "protected"
        message.startsWith("Write ages") -> "ages"
        else -> "other"
    }

    /** One line on what is wrong with the scenes of an analysis, for the note and for the retry. */
    internal fun sceneProblem(scenes: List<Scene>, n: Int, text: String, noVoices: Boolean): String = when {
        scenes.isEmpty() -> "no scene lines were found (lines must start with S1, S2, ...)" +
            if (!text.contains(RewritePrompts.SCENES)) "; the " + RewritePrompts.SCENES + " heading is missing, the reply may have been cut off" else ""
        scenes.any { it.from > it.to } -> "a scene range runs backwards"
        scenes.first().from != 1 -> "the first scene starts at P${scenes.first().from}, not P1"
        scenes.last().to != n -> "the last scene ends at P${scenes.last().to}, but the chapter has $n paragraphs (P$n is the last)"
        else -> scenes.zipWithNext().firstOrNull { (a, b) -> b.from != a.to + 1 }
            ?.let { (a, b) -> "scene ending at P${a.to} is followed by one starting at P${b.from}; each scene must start at the paragraph after the previous one ends" }
            ?: "the scene ranges are unusable"
    } + if (noVoices) "; no VOICES section was found" else ""

    /** Scenes in ~800-word chunks at paragraph boundaries, when the analysis is unusable. */
    internal fun fallbackPlan(paras: List<String>): Plan {
        val scenes = mutableListOf<Scene>()
        var start = 1
        var words = 0
        paras.forEachIndexed { i, p ->
            words += Fidelity.wordCount(p)
            if (words >= 800 || i == paras.lastIndex) {
                scenes += Scene(start, i + 1)
                start = i + 2
                words = 0
            }
        }
        return Plan(emptyList(), scenes, emptyList())
    }

    private const val SCENE_MIN = 500
    private const val SCENE_JOIN_MAX = 1100

    /** Two neighbouring scenes as one, keeping both briefs: what each wants, turns on and ends on. */
    internal fun join(a: Scene, b: Scene): Scene {
        fun both(x: String, y: String, sep: String = "; ") = listOf(x, y).filter { it.isNotBlank() && !it.equals("none", true) }.distinct().joinToString(sep)
        return a.copy(
            to = b.to,
            texture = both(a.texture, b.texture), sharpen = both(a.sharpen, b.sharpen),
            wants = both(a.wants, b.wants), turn = both(a.turn, b.turn, ", then "),
            dialogue = both(a.dialogue, b.dialogue), cut = both(a.cut, b.cut),
            end = b.end.ifBlank { a.end },
            pace = if (a.pace == "loud" || b.pace == "loud") "loud" else a.pace,
        )
    }

    /** Splits scenes over 1,300 source words and joins short neighbours into one scene with both briefs. */
    private fun balance(scenes: List<Scene>, paras: List<String>): List<Scene> {
        fun words(s: Scene) = paras.subList(s.from - 1, s.to).sumOf { Fidelity.wordCount(it) }
        val split = scenes.flatMap { s ->
            if (words(s) <= 1300) return@flatMap listOf(s)
            fallbackPlan(paras.subList(s.from - 1, s.to)).scenes.map { c -> s.copy(from = s.from + c.from - 1, to = s.from + c.to - 1) }
        }
        val out = mutableListOf<Scene>()
        for (s in split) {
            val last = out.lastOrNull()
            // Fewer, fuller scenes mean fewer requests: a short scene joins its neighbour while the pair stays a good size.
            val joins = last != null && (words(s) < 150 ||
                ((words(last) < SCENE_MIN || words(s) < SCENE_MIN) && words(last) + words(s) <= SCENE_JOIN_MAX))
            if (last != null && joins) out[out.lastIndex] = join(last, s) else out += s
        }
        if (out.size > 1 && words(out.first()) < 150) {
            out[1] = join(out[0], out[1])
            out.removeAt(0)
        }
        return out
    }

    /**
     * Strips what a model sometimes wraps prose in (reasoning, fences, a heading, markup junk) and
     * puts every special kind of content into the one form the reader styles:
     * - a bracketed notice, even one wrapped over several lines, becomes bold quote lines;
     * - a run of 3 or more `Key: Value` lines is a stats screen, so it becomes bold quote lines too;
     * - a `Name: "..."` line is a chat message, `> **Name:** "..."`;
     * and the lines of one screen or one chat log are pulled together into one block.
     */
    internal fun clean(raw: String): String {
        val text = digitAges(raw).replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?m)^```.*$"), "")
            .replace(Regex("(?m)^=== .* ===$"), "")
            .replace(Regex("""\$\\(?:rightarrow|to)\$"""), "→")
            .trim()
            .let { if (it.startsWith("# ")) it.substringAfter('\n', "").trim() else it }
        val lines = text.lines()
        val stats = statLines(lines)
        val out = mutableListOf<String>()
        var openUntil = -1
        for ((i, line) in lines.withIndex()) {
            val bare = bare(line)
            val probe = bare.replace("\\[", "[").replace("\\]", "]")
            out += when {
                line.isBlank() -> line
                i <= openUntil -> "> **$bare**"
                probe.length > 2 && probe.first() in "[<【" && probe.last() in "]>】" -> "> **$bare**"
                probe.firstOrNull() in listOf('[', '【') -> {
                    // A notice wrapped over several lines: find where it closes, within a screen's reach.
                    val close = (i + 1..minOf(lines.lastIndex, i + 12)).firstOrNull { j ->
                        bare(lines[j]).replace("\\]", "]").let { it.endsWith("]") || it.endsWith("】") }
                    }
                    if (close != null) {
                        openUntil = close
                        "> **$bare**"
                    } else {
                        line
                    }
                }
                i in stats -> "> **$bare**"
                !line.trimStart().startsWith(">") && chatLine.matches(line.trim()) ->
                    chatLine.find(line.trim())!!.let { "> **${it.groupValues[1]}:** ${it.groupValues[2]}" }
                else -> line
            }
        }
        return out.joinToString("\n")
            .replace(Regex("""(?m)^\*\*\s*\*\*$"""), "")
            // A blank line between two lines of one screen or one chat log would split the block.
            .replace(Regex("""(?m)^(> \*\*.*)\n\n(?=> \*\*)"""), "$1\n")
            .replace(Regex("""(?m)^(> \*\*.*)\n\n(?=> \*\*)"""), "$1\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private val chatLine = Regex("""^([A-Z][\p{L}'’.-]*(?: [A-Z][\p{L}'’.-]*){0,3}):\s*([“"].+)$""")
    private val statLine = Regex("""^([A-Z][\p{L}]*(?: [\p{L}]+){0,3}):\s*([^“"]{1,60})$""")

    /** A line without its quote marker and outer bold, as the source would print it. */
    private fun bare(line: String): String = line.trim().removePrefix(">").trim().removeSurrounding("**").trim()

    /** Indices of lines in runs of 3 or more `Key: Value` lines (blank lines between them allowed). */
    private fun statLines(lines: List<String>): Set<Int> {
        val out = mutableSetOf<Int>()
        val run = mutableListOf<Int>()
        fun close() {
            if (run.size >= 3) out += run
            run.clear()
        }
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val b = bare(line)
            if (statLine.matches(b) && !b.endsWith(":")) run += i else close()
        }
        close()
        return out
    }
}
