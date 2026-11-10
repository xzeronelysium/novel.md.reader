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

    /** One sheet as last read, so opening a chapter does not re-read every file on disk. */
    private class Sheet(val kind: String, val name: String, val lines: List<String>)

    private var sheets: List<Sheet> = emptyList()
    private var sheetsVersion = -1
    private var version = 0
    private var sheetBuilds = 0

    @Synchronized
    fun built(): Set<String> = file("_built.txt").takeIf { it.isFile }?.readLines()?.toSet().orEmpty()

    /** Forgets everything read so far; the next rewrite builds the sheets again. */
    @Synchronized
    fun clear() {
        root.deleteRecursively()
        invalidateSheets()
    }

    @Synchronized
    fun markBuilt(uri: String) {
        if (uri in built()) return
        file("_built.txt").apply { parentFile?.mkdirs() }.appendText(uri + "\n")
    }

    /**
     * Merges `## people/Name` style sections into the sheets; returns how many bullets were added.
     * A section naming a thing the wiki already knows under another name (a nickname, a surname, a
     * title) lands on that thing's sheet, never on a new one; see [canonical].
     */
    @Synchronized
    fun merge(updates: String): Int = mergeClean(tidy(updates)).also { invalidateSheets() }

    /**
     * Gemma-sized models dress replies up: code fences, bold headings, "1." numbering, "* " bullets,
     * a "Sure, here are the sheets" opener. Strip that so the strict format still parses.
     */
    internal fun tidy(reply: String): String = reply.lines().map { raw ->
        var l = raw.trim().replace("**", "").replace("__", "")
        l = l.replace(Regex("^#{1,6}\\s*"), "## ").takeIf { raw.trim().startsWith("#") } ?: l
        l = l.replace(Regex("^(?:[*•]|\\d+[.)])\\s+"), "- ")
        l
    }.filterNot { it.startsWith("```") }.joinToString("\n")

    /** True when [reply] holds at least one parseable heading with a cited bullet; false means ask again. */
    fun usable(reply: String): Boolean = tidy(reply).lines().let { ls ->
        ls.any { it.startsWith("## ") && targetOf(it.removePrefix("## ").trim()) != null } &&
            ls.any { it.startsWith("- ") && citation.containsMatchIn(it) }
    }

    /** Sheet files as last read; refreshed only when [merge] or [clear] changes them. */
    @Synchronized
    private fun sheetCache(): List<Sheet> {
        if (sheetsVersion == version) return sheets
        sheets = KINDS.flatMap { kind ->
            File(root, kind).listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
                val lines = runCatching { f.readLines() }.getOrNull() ?: return@mapNotNull null
                Sheet(kind, f.nameWithoutExtension, lines)
            }
        }
        sheetsVersion = version
        sheetBuilds++
        return sheets
    }

    @Synchronized
    private fun invalidateSheets() {
        version++
    }

    /** How many times the sheets were read from disk; a seam for tests. */
    internal fun sheetCacheBuilds(): Int = synchronized(this) { sheetBuilds }

    private fun mergeClean(updates: String): Int {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var heading: String? = null
        for (raw in updates.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                heading = line.removePrefix("## ").trim()
            } else if (line.startsWith("- ") && heading != null && targetOf(heading) != null &&
                (citation.containsMatchIn(line) || (heading.equals("synopsis", true) && synopsisLine.containsMatchIn(line)))
            ) {
                sections.getOrPut(heading) { mutableListOf() }.add(line)
            }
        }
        var added = 0
        for ((head, bullets) in sections) {
            var target = targetOf(head) ?: continue
            val kind = KINDS.firstOrNull { head.startsWith("$it/", true) }
            var lines = bullets.toList()
            if (kind != null) {
                lines = lines.mapNotNull { dropForeignAliases(kind, head.substringAfter('/').trim(), it) }
                val given = head.substringAfter('/').trim()
                val (file, folded) = canonical(kind, given, bullets)
                target = file ?: continue
                // The name the chapter used becomes an alias of the sheet it landed on.
                if (!given.equals(target.nameWithoutExtension, true) &&
                    aliases(target.takeIf { it.isFile }?.readLines().orEmpty()).none { it.equals(given, true) }
                ) {
                    val cite = CITATION_TAIL.find(bullets.first())?.value ?: "(Ch 0)"
                    lines = listOf("- also called: $given $cite") + lines
                }
                folded.forEach { fold(it, target) }
            }
            added += append(target, lines)
        }
        return added
    }

    /**
     * An "also called" bullet may not list another sheet's title: a model that calls the narrator
     * "The Hero" while a sheet "The Hero" exists is guessing, and two characters must not fuse on a guess.
     */
    private fun dropForeignAliases(kind: String, own: String, bullet: String): String? {
        if (!bullet.startsWith("- also called:", ignoreCase = true)) return bullet
        val titles = File(root, kind).listFiles { f -> f.extension == "md" }.orEmpty().map { norm(it.nameWithoutExtension) }.toSet()
        val cite = CITATION_TAIL.find(bullet)?.value.orEmpty()
        val names = bullet.substringAfter(':').substringBefore("(Ch").split(',', ';').map { it.trim() }
            .filter { it.isNotEmpty() && (norm(it) !in titles || norm(it) == norm(own)) }
        return if (names.isEmpty()) null else "- also called: ${names.joinToString(", ")} $cite".trim()
    }

    /** Appends the bullets of [bullets] that [file] doesn't already say; returns how many were new. */
    private fun append(file: File, bullets: List<String>): Int {
        file.parentFile?.mkdirs()
        val seen = (if (file.isFile) file.readLines().filter { it.startsWith("- ") } else emptyList())
            .map(::tokens).toMutableList()
        val existing = if (file.isFile) file.readLines().toSet() else emptySet()
        val fresh = bullets.filter { bullet ->
            // A state line is a new state even when it reads like the last one.
            if (bullet.startsWith("- now:", ignoreCase = true)) return@filter bullet !in existing
            val t = tokens(bullet)
            (seen.none { sameFact(it, t) }).also { if (it) seen += t }
        }
        if (fresh.isEmpty()) return 0
        if (!file.isFile) file.writeText("# ${file.nameWithoutExtension}\n\n")
        file.appendText(fresh.joinToString("\n", postfix = "\n"))
        return fresh.size
    }

    /**
     * The sheet a section about [name] belongs on, and the older sheets that turn out to be the same
     * thing (to be folded into it). The title and the aliases of every sheet are its names; a
     * single word that sits in exactly one longer title ("Raze" in "Raze Grimhold") is that sheet,
     * and a longer name that contains exactly one existing short title replaces it. When two sheets
     * both fit, none is chosen: two people sharing a first name stay two people.
     */
    private fun canonical(kind: String, name: String, bullets: List<String>): Pair<File?, List<File>> {
        val dir = File(root, kind)
        val sheets = dir.listFiles { f -> f.extension == "md" }.orEmpty()
            .associateWith { f -> (listOf(f.nameWithoutExtension) + aliases(f.readLines())).map(::norm) }
        fun named(n: String) = sheets.filter { (_, names) -> n in names }.keys
        val own = norm(name)
        named(own).firstOrNull()?.let { return it to emptyList() }
        // The chapter itself says this is the same as something we have.
        for (claim in aliases(bullets).map(::norm).filter { it.length >= 3 }) {
            val hit = named(claim)
            if (hit.size == 1) return hit.first() to emptyList()
        }
        val words = own.split(' ')
        if (words.size == 1 && own.length >= 3) {
            val hit = sheets.filter { (f, names) -> names.any { n -> ' ' in n && own in n.split(' ') } || f.nameWithoutExtension.let { norm(it).split(' ').let { w -> w.size > 1 && own in w } } }.keys
            if (hit.size == 1) return hit.first() to emptyList()
        } else if (words.size > 1) {
            val short = sheets.filter { (_, names) -> names.any { n -> n.length >= 3 && ' ' !in n && n in words } }.keys
            val target = sheet(kind, name)
            if (short.size == 1 && target != null) return target to short.toList()
        }
        return sheet(kind, name) to emptyList()
    }

    /** Moves everything [from] knows onto [into] and removes [from], keeping its title as an alias. */
    private fun fold(from: File, into: File) {
        if (from == into || !from.isFile) return
        val bullets = from.readLines().filter { it.startsWith("- ") }
        val cite = bullets.firstNotNullOfOrNull { CITATION_TAIL.find(it)?.value } ?: "(Ch 0)"
        append(into, listOf("- also called: ${from.nameWithoutExtension} $cite") + bullets)
        from.delete()
    }

    private fun norm(s: String) = s.lowercase().replace('’', '\'').trim()

    /** Every sheet and what it answers to, for the extraction prompt: the model reuses these names. */
    @Synchronized
    fun roster(budget: Int = 2_500): String {
        val sheets = sheetCache()
        val out = StringBuilder()
        for (kind in KINDS) {
            val line = sheets.filter { it.kind == kind }.joinToString("; ") { sheet ->
                val al = aliases(sheet.lines).take(5)
                sheet.name + if (al.isEmpty()) "" else " (also ${al.joinToString(", ")})"
            }
            if (line.isNotEmpty()) out.append("$kind: $line\n")
        }
        return out.toString().take(budget).trim()
    }

    /** The sheets that matter for [text]: every person or place it names (or their aliases), plus threads and conflicts. */
    @Synchronized
    fun context(text: String, budget: Int = 14_000): String {
        val lower = text.lowercase().replace('’', '\'')
        val sheets = sheetCache()
        val hits = KINDS.flatMap { kind ->
            sheets.filter { it.kind == kind }.mapNotNull { sheet ->
                val names = listOf(sheet.name) + aliases(sheet.lines)
                // Whole words only: a sheet called "Lin" must not answer to "line".
                val count = names.map { it.lowercase().replace('’', '\'') }.filter { it.length >= 3 }
                    .sumOf { Regex("(?<!\\p{L})" + Regex.escape(it) + "(?!\\p{L})").findAll(lower).count() }
                if (count > 0) Triple(kind, trim(sheet.lines), count) else null
            }
        }.sortedByDescending { it.third }
        val out = StringBuilder()
        roster().takeIf { it.isNotEmpty() }?.let { out.append("[sheets that exist; reuse these names, add new nicknames as 'also called']\n").append(it).append("\n\n") }
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
        // Hard figures come first and in full: a drifted age or span is the costliest slip.
        file("figures.md").takeIf { it.isFile }?.let { out.append(recentLines(it, 4_000)).append("\n\n") }
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

    // ---- Reading side: what the reader's wiki shows, never past the chapter being read ----

    /** Every person, place or system with at least one fact cited at or before [upTo]. */
    @Synchronized
    fun index(upTo: Int): WikiIndex = WikiIndex(
        sheetCache().mapNotNull { sheet ->
            val visible = sheet.lines.filter { it.startsWith("- ") && firstChapter(it)?.let { n -> n <= upTo } == true }
            if (visible.isEmpty()) null else WikiEntry(sheet.kind, sheet.name, aliases(visible))
        }.groupBy { it.name.lowercase() }.values.map { same ->
            // One name on two shelves (a place that is also a system) is one entry; the page merges both.
            same.minByOrNull { KINDS.indexOf(it.kind) }!!.copy(aliases = same.flatMap { it.aliases }.distinct())
        }.sortedBy { it.name.lowercase() }
    )

    /** One sheet as the reader sees it at chapter [upTo]; null when nothing of it is known yet. */
    @Synchronized
    fun page(kind: String, name: String, upTo: Int): WikiPage? {
        val files = KINDS.mapNotNull { k -> sheet(k, name)?.takeIf { it.isFile } }
            .sortedBy { if (it.parentFile?.name == kind) 0 else 1 }
        val f = files.firstOrNull() ?: return null
        val facts = files.flatMap { it.readLines() }.filter { it.startsWith("- ") }.mapNotNull { line ->
            val chapter = firstChapter(line) ?: return@mapNotNull null
            if (chapter > upTo) return@mapNotNull null
            val body = line.removePrefix("- ").substringBeforeLast("(Ch").trim()
            val (type, text) = when {
                body.startsWith("now:", true) -> WikiFact.NOW to body.substringAfter(':').trim()
                body.startsWith("voice:", true) -> WikiFact.VOICE to body.substringAfter(':').trim()
                body.startsWith("also called:", true) -> WikiFact.ALIAS to body.substringAfter(':').trim()
                else -> WikiFact.FACT to body
            }
            val tag = TAG.find(text)?.groupValues?.get(1)?.lowercase()?.let { TAG_SYNONYMS[it] ?: it }?.takeIf { type == WikiFact.FACT }
            WikiFact(type, if (tag != null) text.replace(TAG, "").trim() else text, chapter, CITATION_TAIL.find(line)?.value?.contains("infer", true) == true, tag)
        }
        if (facts.isEmpty()) return null
        return WikiPage(kind, f.nameWithoutExtension, facts.sortedBy { it.chapter })
    }

    /** The story so far, one line per chapter up to [upTo]. */
    @Synchronized
    fun storySoFar(upTo: Int): List<String> =
        file("synopsis.md").takeIf { it.isFile }?.readLines().orEmpty()
            .filter { it.startsWith("- ") && (firstChapter(it)?.let { n -> n <= upTo } == true) }
            .map { it.removePrefix("- ").trim() }

    /** The lowest chapter a bullet cites: the point from which a reader knows it. */
    private fun firstChapter(line: String): Int? =
        CITATION_TAIL.find(line)?.value?.let { Regex("""\d+""").findAll(it).firstOrNull()?.value?.toIntOrNull() }
            ?: SYNOPSIS_NUMBER.find(line)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        val KINDS = listOf("people", "places", "systems")
        /** The citation closing a bullet: `(Ch 12)`, `(Ch 12, inferred)`, `(Ch 3, Ch 5)`. */
        val CITATION_TAIL = Regex("""\(\s*Ch(?:apters?|\.)?\s*\d+[^)]*\)\s*$""", RegexOption.IGNORE_CASE)
        val TAG = Regex("""^\[(appearance|looks?|personality|traits?|abilit(?:y|ies)|skills?|relation(?:ship)?s?|background|history|role|rules?)]\s*""", RegexOption.IGNORE_CASE)
        /** Other words a model writes for a tag, mapped to the tag's own. */
        val TAG_SYNONYMS = mapOf("look" to "appearance", "looks" to "appearance", "trait" to "personality", "traits" to "personality",
            "abilities" to "ability", "skill" to "ability", "skills" to "ability", "relationship" to "relation", "relations" to "relation",
            "relationships" to "relation", "history" to "background", "rule" to "rules")
        val SYNOPSIS_NUMBER = Regex("""^- Ch\s*(\d+)\s*:""", RegexOption.IGNORE_CASE)
    }

    private fun targetOf(heading: String): File? {
        val h = heading.trim()
        return when {
            h.equals("threads", true) -> file("threads.md")
            h.equals("conflicts", true) -> file("conflicts.md")
            h.equals("synopsis", true) -> file("synopsis.md")
            h.equals("figures", true) -> file("figures.md")
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

/** A person, place or system the wiki has a sheet for; [aliases] are the other names it answers to. */
data class WikiEntry(val kind: String, val name: String, val aliases: List<String> = emptyList())

/** One fact on a sheet: [chapter] is where it was learned, [inferred] marks a guess rather than a statement. */
data class WikiFact(val type: String, val text: String, val chapter: Int, val inferred: Boolean, val tag: String? = null) {
    companion object {
        const val NOW = "now"
        const val VOICE = "voice"
        const val ALIAS = "alias"
        const val FACT = "fact"
    }
}

data class WikiPage(val kind: String, val name: String, val facts: List<WikiFact>) {
    /** The newest recorded state. */
    val now: WikiFact? get() = facts.lastOrNull { it.type == WikiFact.NOW }
    val voice: List<WikiFact> get() = facts.filter { it.type == WikiFact.VOICE }
    val aliases: List<String> get() = facts.filter { it.type == WikiFact.ALIAS }
        .flatMap { it.text.split(',', ';') }.map { it.trim() }.filter { it.isNotEmpty() }
    val known: List<WikiFact> get() = facts.filter { it.type == WikiFact.FACT && !it.inferred }
    /** The first chapter that mentions it. */
    val firstSeen: Int get() = facts.minOf { it.chapter }
    /** The known facts under one tag, in the order they were learned. */
    fun known(tag: String?): List<WikiFact> = known.filter { it.tag == tag }
    val inferred: List<WikiFact> get() = facts.filter { it.type == WikiFact.FACT && it.inferred }
    /** Earlier states, oldest first, for "how they changed". */
    val history: List<WikiFact> get() = facts.filter { it.type == WikiFact.NOW }.dropLast(1)
}

/** Finds the wiki's names in a piece of prose. Whole words only, longest name first. */
class WikiIndex(val entries: List<WikiEntry>) {
    private val byName: Map<String, WikiEntry> = buildMap<String, WikiEntry> {
        // Titles beat aliases when two sheets claim the same word.
        this@WikiIndex.entries.forEach { e -> e.aliases.forEach { putIfAbsent(norm(it), e) } }
        this@WikiIndex.entries.forEach { e -> put(norm(e.name), e) }
    }.filterKeys { it.length >= 3 }

    private val pattern: Regex? = byName.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.let { names -> Regex("(?<!\\p{L})(?:" + names.joinToString("|") { Regex.escape(it) } + ")(?!\\p{L})", RegexOption.IGNORE_CASE) }

    fun isEmpty() = entries.isEmpty()

    /** `[start, end)` ranges of [text] that name a sheet. */
    fun find(text: String): List<Triple<Int, Int, WikiEntry>> {
        val re = pattern ?: return emptyList()
        return re.findAll(text.replace('’', '\'')).mapNotNull { m ->
            byName[norm(m.value)]?.let { Triple(m.range.first, m.range.last + 1, it) }
        }.toList()
    }

    fun lookup(name: String): WikiEntry? = byName[norm(name)]

    private fun norm(s: String) = s.lowercase().replace('’', '\'').trim()
}
