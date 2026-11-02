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
    )

    /** [keep]: spoken phrases whose exact wording matters, such as catchphrases and wordplay. */
    data class Plan(val beats: List<Beat>, val scenes: List<Scene>, val flags: List<String>, val keep: List<String> = emptyList())

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
        val plan = runCatching {
            parsePlan(
                RewriteClient.stream(settings, RewritePrompts.ANALYSE, memory + "SOURCE CHAPTER: $title\n\n$numbered", onProgress),
                paras,
            )
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            fallbackPlan(paras)
        }
        if (plan.beats.isEmpty()) warnings += "the planning step gave no beats; scenes were cut by length and beats were not checked"

        // 2. Write, scene by scene.
        val known = sheets + "\n" + previousNote.orEmpty() + "\n" + previousTail.orEmpty() + "\n" + title
        val written = mutableListOf<String>()
        var repairs = 0
        for ((i, scene) in plan.scenes.withIndex()) {
            val label = "SCENE ${i + 1}/${plan.scenes.size}"
            onStage("WRITING $label")
            val source = paras.subList(scene.from - 1, scene.to).joinToString("\n\n")
            val beats = plan.beats.filter { it.from in scene.from..scene.to }
            val keep = plan.keep.filter { Fidelity.containsQuotedWording(source, it) }
            val (minWords, maxWords) = RewriteClient.lengthTarget(settings.strength, Fidelity.wordCount(source))
            val before = if (i == 0) previousTail?.let { "END OF THE PREVIOUS CHAPTER (pick up from it, do not repeat it):\n$it" }
            else "THE SCENE JUST BEFORE ENDS (continue from it, do not repeat it):\n" + RewriteClient.tailWords(written.last(), 150)
            val user = buildString {
                append(memory)
                if (before != null) append(before).append("\n\n")
                append("PLAN FOR THIS SCENE: type ${scene.type}; register ${scene.register}; texture ${scene.texture}; sharpen ${scene.sharpen}; anchor ${scene.anchor}\n\n")
                if (beats.isNotEmpty()) append("BEATS THAT MUST HAPPEN, IN ORDER:\n").append(beats.joinToString("\n") { "- ${it.text} (cause: ${it.cause})" }).append("\n\n")
                if (keep.isNotEmpty()) append("PROTECTED PHRASES (retain wording in the source's original context and role; other wording may improve. If the plan selected a thought or label, keep it as such, never force it into speech):\n").append(keep.joinToString("\n") { "- $it" }).append("\n\n")
                if (i > 0) append("If this scene opens at a new time or place compared with the scene before, start it with a line containing only ---.\n\n")
                if (i == plan.scenes.lastIndex) {
                    append("THIS IS THE LAST SCENE OF THE CHAPTER. End on the same moment, line and feeling as the source's last paragraph; add nothing after it.\n\n")
                }
                append("LENGTH: the source scene has ${Fidelity.wordCount(source)} words. Write at least $minWords words and at most about $maxWords.\n\n")
                append("SOURCE SCENE (Ch $number, $title):\n\n").append(source)
            }
            val system = RewritePrompts.WRITE + "\n\n" + (RewritePrompts.METHODS[scene.type] ?: "") + "\n\n" + RewriteClient.strengthRule(settings.strength)

            var draft = clean(RewriteClient.stream(settings, system, user, onProgress, RewriteClient.CREATIVE))
            val previousScene = written.lastOrNull().orEmpty()
            val soFar = written.joinToString("\n\n")
            var problems = Fidelity.sceneProblems(source, draft, beats.map { it.text }, minWords, known, previousScene, soFar, keep)
            if (problems.isNotEmpty()) {
                onStage("REPAIRING $label")
                repairs++
                val retry = user + "\n\n---\n\nYOUR DRAFT OF THIS SCENE WAS REJECTED FOR THESE REASONS:\n" +
                    problems.joinToString("\n") { "- $it" } + "\n\nYOUR DRAFT:\n" + draft +
                    "\n\nWrite the whole scene again, fixing exactly these problems and keeping what was good."
                val second = runCatching { clean(RewriteClient.stream(settings, system, retry, onProgress, RewriteClient.CREATIVE)) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                if (second != null && second.isNotBlank()) {
                    val secondProblems = Fidelity.sceneProblems(source, second, beats.map { it.text }, minWords, known, previousScene, soFar, keep)
                    if (secondProblems.size < problems.size ||
                        (secondProblems.size == problems.size && Fidelity.wordCount(second) > Fidelity.wordCount(draft))
                    ) {
                        draft = second
                        problems = secondProblems
                    }
                }
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
            append(plan.scenes.withIndex().joinToString("\n") { (i, s) -> "S${i + 1} | P${s.from}-P${s.to} | ${s.type} | ${s.register} | ${s.texture} | ${s.sharpen} | ${s.anchor}" })
            if (plan.keep.isNotEmpty()) append("\n\n## Protected dialogue\n").append(plan.keep.joinToString("\n") { "- $it" })
            if (warnings.isNotEmpty()) append("\n\n## Fidelity warnings\n").append(warnings.joinToString("\n") { "- $it" })
            append("\n\n## Run\n- prompt ${RewriteClient.PROMPT_VERSION}\n- model ${settings.model}")
            if (settings.effort.isNotBlank()) append(" (effort ${settings.effort})")
            append("\n- strength ${settings.strength}\n- scenes ${plan.scenes.size}, repairs $repairs")
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

        val beats = section(RewritePrompts.BEATS, RewritePrompts.SCENES, RewritePrompts.FLAGS, RewritePrompts.KEEP).lines().mapNotNull { line ->
            val body = Regex("""^\s*\d+[.)]\s*(.+)""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val r = range.find(body)
            val from = r?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, maxOf(n, 1)) ?: return@mapNotNull null
            val to = r.groupValues[2].toIntOrNull()?.coerceIn(from, maxOf(n, 1)) ?: from
            val rest = body.substring(r.range.last + 1).trim()
            Beat(from, to, rest.substringBefore("| cause:").substringBefore("|").trim(), rest.substringAfter("cause:", "").trim())
        }

        val scenes = section(RewritePrompts.SCENES, RewritePrompts.FLAGS, RewritePrompts.KEEP).lines().mapNotNull { line ->
            if (!line.trim().startsWith("S", ignoreCase = true)) return@mapNotNull null
            val parts = line.split('|').map { it.trim() }
            val r = parts.firstNotNullOfOrNull { range.find(it)?.takeIf { m -> it.startsWith("P") || it.startsWith("[P") } } ?: return@mapNotNull null
            val from = r.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val to = r.groupValues[2].toIntOrNull() ?: from
            fun field(name: String) = parts.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
            Scene(from, to, field("type").lowercase().takeIf { it in RewritePrompts.METHODS } ?: "inner",
                field("register"), field("texture"), field("sharpen"), field("anchor"))
        }

        val flags = section(RewritePrompts.FLAGS, RewritePrompts.KEEP).lines().map { it.trim() }
            .filter { it.startsWith("- ") && !it.removePrefix("- ").trim().equals("none", true) }
            .map { it.removePrefix("- ").trim() }
        // Only lines the source really prints: a "keep" line the model misquoted would be impossible to meet.
        val sourceText = paras.joinToString("\n\n")
        val keep = section(RewritePrompts.KEEP).lines().map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().removeSurrounding("\"").removeSurrounding("“", "”")
                .removeSurrounding("'").removeSurrounding("‘", "’").trim() }
            .filter { it.isNotEmpty() && !it.equals("none", true) && Fidelity.containsQuotedWording(sourceText, it) }
            .distinct()

        val valid = scenes.isNotEmpty() && scenes.first().from == 1 && scenes.last().to == n &&
            scenes.zipWithNext().all { (a, b) -> b.from == a.to + 1 } && scenes.all { it.from <= it.to }
        return Plan(beats, if (valid) balance(scenes, paras) else fallbackPlan(paras).scenes, flags, keep)
    }

    /** Scenes in ~600-word chunks at paragraph boundaries, when the analysis is unusable. */
    internal fun fallbackPlan(paras: List<String>): Plan {
        val scenes = mutableListOf<Scene>()
        var start = 1
        var words = 0
        paras.forEachIndexed { i, p ->
            words += Fidelity.wordCount(p)
            if (words >= 600 || i == paras.lastIndex) {
                scenes += Scene(start, i + 1)
                start = i + 2
                words = 0
            }
        }
        return Plan(emptyList(), scenes, emptyList())
    }

    /** Splits scenes over 900 source words and folds scenes under 150 words into the one before. */
    private fun balance(scenes: List<Scene>, paras: List<String>): List<Scene> {
        fun words(s: Scene) = paras.subList(s.from - 1, s.to).sumOf { Fidelity.wordCount(it) }
        val split = scenes.flatMap { s ->
            if (words(s) <= 900) return@flatMap listOf(s)
            fallbackPlan(paras.subList(s.from - 1, s.to)).scenes.map { c -> s.copy(from = s.from + c.from - 1, to = s.from + c.to - 1) }
        }
        val out = mutableListOf<Scene>()
        for (s in split) {
            val last = out.lastOrNull()
            if (last != null && words(s) < 150) out[out.lastIndex] = last.copy(to = s.to) else out += s
        }
        if (out.size > 1 && words(out.first()) < 150) {
            out[1] = out[1].copy(from = out[0].from)
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
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
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
