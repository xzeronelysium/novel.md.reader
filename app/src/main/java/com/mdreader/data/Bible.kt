package com.mdreader.data

import java.io.File

/**
 * A novel's memory, kept in app-private storage beside its rewrites:
 *
 * ```
 * bible/people/<name>.md   appearance, how they speak, role, traits, relations
 * bible/places/<name>.md   layout, look, light, sound, landmarks
 * bible/systems/<name>.md  ranks, rules, costs and currencies of the world
 * bible/synopsis.md        one line per chapter: the story so far
 * bible/threads.md         open promises, questions and the causes behind events
 * bible/conflicts.md       contradictions and lines that don't sound like their speaker
 * bible/_built.txt         chapters already read into the sheets
 * ```
 *
 * A person's `- now:` lines record their state; the newest one is the one that counts.
 *
 * Every fact must end with a chapter citation such as `(Ch 12)`; a synopsis line starts with
 * `Ch 12:` instead. A bullet without one is rejected on merge, so the sheets can only hold what a
 * chapter actually said.
 */
class BibleStore(private val root: File) {

    private val synopsisLine = Regex("""^- Ch\s*\d+\s*:""", RegexOption.IGNORE_CASE)

    private val citation = Regex("""\(\s*Ch(?:apters?|\.)?\s*\d+""", RegexOption.IGNORE_CASE)

    @Synchronized
    fun built(): Set<String> = file("_built.txt").takeIf { it.isFile }?.readLines()?.toSet().orEmpty()

    /** Forgets everything read so far; the next rewrite builds the sheets again. */
    @Synchronized
    fun clear() {
        root.deleteRecursively()
    }

    @Synchronized
    fun markBuilt(uri: String) {
        if (uri in built()) return
        file("_built.txt").apply { parentFile?.mkdirs() }.appendText(uri + "\n")
    }

    /** Merges `## people/Name` style sections into the sheets; returns how many bullets were added. */
    @Synchronized
    fun merge(updates: String): Int {
        var target: File? = null
        var added = 0
        val pending = LinkedHashMap<File, MutableList<String>>()
        for (raw in updates.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                target = targetOf(line.removePrefix("## ").trim())
            } else if (line.startsWith("- ") && target != null &&
                (citation.containsMatchIn(line) || (target.name == "synopsis.md" && synopsisLine.containsMatchIn(line)))
            ) {
                pending.getOrPut(target) { mutableListOf() }.add(line)
            }
        }
        for ((file, bullets) in pending) {
            file.parentFile?.mkdirs()
            val seen = (if (file.isFile) file.readLines().filter { it.startsWith("- ") } else emptyList())
                .map(::tokens).toMutableList()
            val lines = if (file.isFile) file.readLines().toSet() else emptySet()
            val fresh = bullets.filter { bullet ->
                // A state line is a new state even when it reads like the last one.
                if (bullet.startsWith("- now:", ignoreCase = true)) return@filter bullet !in lines
                val t = tokens(bullet)
                (seen.none { sameFact(it, t) }).also { if (it) seen += t }
            }
            if (fresh.isEmpty()) continue
            if (!file.isFile) file.writeText("# ${file.nameWithoutExtension}\n\n")
            file.appendText(fresh.joinToString("\n", postfix = "\n"))
            added += fresh.size
        }
        return added
    }

    /** The sheets that matter for [text]: every person or place it names (or their aliases), plus threads and conflicts. */
    @Synchronized
    fun context(text: String, budget: Int = 14_000): String {
        val lower = text.lowercase().replace('’', '\'')
        val hits = listOf("people", "places", "systems").flatMap { kind ->
            File(root, kind).listFiles { f -> f.extension == "md" }.orEmpty().mapNotNull { f ->
                val lines = f.readLines()
                val names = listOf(f.nameWithoutExtension) + aliases(lines)
                // Whole words only: a sheet called "Lin" must not answer to "line".
                val count = names.map { it.lowercase().replace('’', '\'') }.filter { it.length >= 3 }
                    .sumOf { Regex("(?<!\\p{L})" + Regex.escape(it) + "(?!\\p{L})").findAll(lower).count() }
                if (count > 0) Triple(kind, trim(lines), count) else null
            }
        }.sortedByDescending { it.third }
        val out = StringBuilder()
        val synopsis = file("synopsis.md")
        if (synopsis.isFile) {
            val recent = synopsis.readLines().filter { it.startsWith("- ") }.takeLast(10)
            if (recent.isNotEmpty()) out.append("[story so far]\n").append(recent.joinToString("\n")).append("\n\n")
        }
        for ((kind, body, _) in hits) {
            // A sheet too big for what is left is skipped, not the end of the memory.
            if (out.length + body.length > budget) continue
            out.append("[$kind] ").append(body.trim()).append("\n\n")
        }
        for (name in listOf("threads.md", "conflicts.md")) {
            val f = file(name)
            if (f.isFile && out.length < budget) out.append(recentLines(f, 2_500)).append("\n\n")
        }
        return out.toString().trim()
    }

    /** The newest whole lines of [file] that fit in [chars], under its heading. */
    private fun recentLines(file: File, chars: Int): String {
        val lines = file.readLines()
        val kept = ArrayDeque<String>()
        var size = 0
        for (line in lines.asReversed()) {
            if (line.startsWith("# ")) break
            if (size + line.length + 1 > chars) break
            kept.addFirst(line)
            size += line.length + 1
        }
        return (listOfNotNull(lines.firstOrNull { it.startsWith("# ") }) + kept).joinToString("\n").trim()
    }

    /** Names a sheet answers to besides its title, from its `- also called: A, B (Ch 3)` bullets. */
    private fun aliases(lines: List<String>): List<String> =
        lines.filter { it.startsWith("- also called:", ignoreCase = true) }
            .flatMap { it.substringAfter(':').substringBefore("(Ch").split(',', ';') }
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** A long sheet keeps its opening facts and its newest ones. */
    private fun trim(all: List<String>): String {
        val state = all.lastOrNull { it.startsWith("- now:", ignoreCase = true) }
        val lines = all.filter { !it.startsWith("- now:", ignoreCase = true) || it == state }
        val facts = lines.filter { it.startsWith("- ") }
        if (facts.size <= 28) return lines.joinToString("\n")
        val head = lines.firstOrNull { it.startsWith("# ") }.orEmpty()
        val rest = facts.filter { it != state }
        return (listOf(head) + listOfNotNull(state) + rest.take(8) + "- …" + rest.takeLast(20)).joinToString("\n")
    }

    /** The meaningful words of a bullet, without its citation. */
    private fun tokens(line: String): Set<String> =
        line.substringBefore("(Ch").lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ').filter { it.length >= 3 }.toSet()

    /** The same fact in other words: most of the shorter bullet's words already sit in the other. */
    private fun sameFact(a: Set<String>, b: Set<String>): Boolean {
        val small = minOf(a.size, b.size)
        if (small < 3) return a == b
        return a.intersect(b).size.toDouble() / small >= 0.85
    }

    private fun targetOf(heading: String): File? {
        val h = heading.trim()
        return when {
            h.equals("threads", true) -> file("threads.md")
            h.equals("conflicts", true) -> file("conflicts.md")
            h.equals("synopsis", true) -> file("synopsis.md")
            h.startsWith("systems/", true) -> sheet("systems", h.substringAfter('/'))
            h.startsWith("people/", true) -> sheet("people", h.substringAfter('/'))
            h.startsWith("places/", true) -> sheet("places", h.substringAfter('/'))
            else -> null
        }
    }

    private fun sheet(kind: String, name: String): File? {
        val safe = Vault.safeTitle(name).takeIf { it != "Untitled" } ?: return null
        return File(File(root, kind), "$safe.md")
    }

    private fun file(name: String) = File(root, name)
}
