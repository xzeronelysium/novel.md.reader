package com.mdreader.data.online

import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

/** Why an online request did not produce usable content. */
class OnlineException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class NovelHit(
    val title: String,
    /** Site path, e.g. `/novel/cultivation-online-novel`. */
    val path: String,
    val meta: String,
    /** The site's score out of 5 to one decimal (`"4.6"`), or empty when it shows none. */
    val rating: String = "",
)

data class ChapterRef(val title: String, val path: String)

data class AuthorListing(val hits: List<NovelHit>, val hasNext: Boolean)

/** One server page of a novel's chapter list. */
data class ChapterPage(
    val page: Int,
    val totalPages: Int,
    val totalChapters: Int,
    val chapters: List<ChapterRef>,
)

data class NovelDetails(
    val title: String,
    val author: String,
    val status: String,
    val genres: List<String>,
    val synopsis: String,
    val totalPages: Int,
    /** Absolute same-site cover image URL, or empty when the page has none. */
    val cover: String = "",
    val authorPath: String = "",
    /** The site's score out of 5 to one decimal, or empty when nobody has voted yet. */
    val rating: String = "",
    val votes: Int = 0,
)

/**
 * Turns Free Web Novel markup into app types. Pure functions with no Android or
 * network types, so they are covered by JVM tests against fixtures.
 *
 * Everything here treats the HTML as untrusted: only expected elements are read,
 * links must be same-site `/novel/...` paths, and no script is ever run.
 */
object FreeWebNovelParser {

    const val ORIGIN = "https://freewebnovel.com"
    // The site caps a list page at 200 chapters, however many are asked for.
    const val PAGE_SIZE = 200

    private val novelPath = Regex("^/novel/[A-Za-z0-9._~-]+$")
    private val chapterPath = Regex("^/novel/[A-Za-z0-9._~-]+/[A-Za-z0-9._~-]+$")

    // The site injects self-promotion into chapter text; drop those paragraphs.
    private val promo = Regex("free\\s?web\\s?novel(\\.?\\s?com)?", RegexOption.IGNORE_CASE)
    // Every text node of a chapter is normalised; compile once, not once per node.
    private val whitespaceRun = Regex("\\s+")

    fun isNovelPath(path: String) = novelPath.matches(path)

    fun isChapterPath(path: String) = chapterPath.matches(path)

    fun isAuthorPath(path: String) = Regex("^/author/(?:[A-Za-z0-9._~+,-]|%[0-9a-fA-F]{2})+$").matches(path) && ".." !in path

    /** Reduces a link to a site path if (and only if) it points at this site. */
    private fun sitePath(href: String): String? {
        val path = when {
            href.startsWith("/") && !href.startsWith("//") -> href
            href.startsWith("$ORIGIN/") -> href.removePrefix(ORIGIN)
            else -> return null
        }
        return path.substringBefore('?').substringBefore('#').trimEnd('/')
    }

    fun parseSearch(html: String): List<NovelHit> {
        val doc = Jsoup.parse(html, ORIGIN)
        val seen = LinkedHashSet<String>()
        return doc.select(".col-content .li .txt h3.tit a[href]").mapNotNull { link ->
            val path = sitePath(link.attr("href"))?.takeIf(::isNovelPath) ?: return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            val row = link.parents().firstOrNull { it.hasClass("txt") }
            val meta = row?.select(".desc .item .right")
                ?.map { it.text().trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(2)
                ?.joinToString(" · ")
                .orEmpty()
            val rating = formatScore(row?.selectFirst(".core span")?.text().orEmpty())
            NovelHit(title = title, path = path, meta = meta, rating = rating)
        }
    }

    /** A score the site shows as text, kept only when it is a real 0 to 5 value (0.0 means unrated). */
    private fun formatScore(raw: String): String {
        val value = raw.trim().takeIf { it.matches(Regex("\\d(\\.\\d+)?")) }?.toDoubleOrNull() ?: return ""
        return if (value > 0.0 && value <= 5.0) String.format(Locale.ROOT, "%.1f", value) else ""
    }

    private val votePattern = Regex("(\\d(?:\\.\\d+)?)\\s*/\\s*5\\s*\\(\\s*([\\d,]+)\\s*votes?\\s*\\)", RegexOption.IGNORE_CASE)

    fun parseNovelDetails(html: String): NovelDetails {
        val doc = Jsoup.parse(html, ORIGIN)
        val title = doc.selectFirst(".m-desc h1.tit")?.text()?.trim().orEmpty()
        if (title.isEmpty()) throw OnlineException("That page doesn't look like a novel page.")
        val options = doc.select("#indexselect option").size
        val info = doc.selectFirst(".m-imgtxt")
        val synopsis = doc.select(".m-desc .txt .inner p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        // "4.0 / 5 ( 21 votes )": shown only when someone has voted and the numbers make sense.
        val vote = votePattern.find(doc.selectFirst(".m-desc .score p.vote")?.text().orEmpty())
        val votes = vote?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull() ?: 0
        val rating = if (votes > 0) formatScore(vote?.groupValues?.get(1).orEmpty()) else ""
        return NovelDetails(
            title = title,
            author = info?.selectFirst("a[href*=/author/]")?.text()?.trim().orEmpty(),
            status = info?.selectFirst("span.s2 a")?.text()?.trim().orEmpty(),
            genres = info?.select("a[href^=/genre/]")?.map { it.text().trim() }.orEmpty(),
            synopsis = synopsis,
            totalPages = options.coerceAtLeast(1),
            cover = coverUrl(doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()),
            authorPath = info?.selectFirst("a[href*=/author/]")?.attr("href")
                ?.let(::sitePath)?.takeIf(::isAuthorPath).orEmpty(),
            rating = rating,
            votes = if (rating.isEmpty()) 0 else votes,
        )
    }

    fun parseAuthorPage(html: String, authorPath: String, page: Int): AuthorListing {
        val doc = Jsoup.parse(html, ORIGIN)
        if (doc.selectFirst(".col-content .ul-list1") == null) {
            throw OnlineException("The author page came back in an unexpected format.")
        }
        val hasNext = doc.select(".pagination a[href], .m-page a[href], .pagelink a[href]").any { link ->
            val href = link.attr("href")
            sitePath(href) == authorPath && Regex("(?:[?&])page=${page + 1}(?:[&#]|$)").containsMatchIn(href)
        }
        return AuthorListing(parseSearch(html), hasNext)
    }

    private val coverPath = Regex("^/files/[A-Za-z0-9._/~-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

    /** Keeps only a cover image hosted on the site itself; anything else is dropped. */
    fun coverUrl(raw: String): String {
        val path = sitePath(raw.trim()) ?: return ""
        return if (coverPath.matches(path) && ".." !in path) ORIGIN + path else ""
    }

    fun isCoverUrl(url: String) = url.startsWith("$ORIGIN/") && coverUrl(url) == url

    /** Parses the `?ajax=chapters` JSON response, validating its own bookkeeping. */
    fun parseChapterPage(json: String): ChapterPage {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            throw OnlineException("The chapter list came back in an unexpected format.", e)
        }
        val html = root.optString("html")
        val page = root.optInt("page", -1)
        val totalPages = root.optInt("totalPage", -1)
        if (html.isEmpty() || page < 1 || totalPages < 1) {
            throw OnlineException("The chapter list came back incomplete.")
        }

        val chapters = Jsoup.parseBodyFragment(html, ORIGIN).select("a[href]").mapNotNull { link ->
            val path = sitePath(link.attr("href"))?.takeIf(::isChapterPath) ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text() }.trim().ifEmpty { "Untitled" }
            ChapterRef(title, path)
        }
        return ChapterPage(page, totalPages, root.optInt("totalChapters", -1), chapters)
    }

    /**
     * Joins pages in page order, never completion order, and refuses lists with
     * a missing page, a repeated page, or a count that disagrees with the site.
     */
    fun assembleChapters(pages: List<ChapterPage>): List<ChapterRef> {
        if (pages.isEmpty()) throw OnlineException("The chapter list was empty.")
        val total = pages.first().totalPages
        val ordered = pages.sortedBy { it.page }
        if (ordered.map { it.page } != (1..total).toList()) {
            throw OnlineException("The chapter list is missing pages. Try again in a moment.")
        }

        val chapters = ordered.flatMap { it.chapters }
        if (chapters.map { it.path }.toSet().size != chapters.size) {
            throw OnlineException("The chapter list contained duplicates. Try again in a moment.")
        }
        val expected = ordered.last().totalChapters
        if (expected >= 0 && expected != chapters.size) {
            throw OnlineException(
                "The site reports $expected chapters but ${chapters.size} were read. Try again later."
            )
        }
        if (chapters.isEmpty()) throw OnlineException("This novel has no chapters yet.")
        return chapters
    }

    /**
     * Reads the chapter body: the direct `<p>` children of `#article`. Ads and
     * scripts live in sibling elements and are never touched. Paragraph order is
     * the document order; nothing is sorted or reflowed.
     */
    fun parseChapter(html: String): List<Block> {
        val doc = Jsoup.parse(html, ORIGIN)
        val article = doc.selectFirst("#article")
            ?: throw OnlineException("The page didn't contain a chapter. The site may be showing an error.")

        val blocks = article.children()
            .filter { it.tagName() == "p" }
            .mapNotNull { paragraph ->
                val spans = spansOf(paragraph)
                val text = spans.joinToString("") { it.text }.trim()
                if (text.isEmpty() || promo.containsMatchIn(text)) null else Block.Paragraph(trimmed(spans))
            }
        if (blocks.isEmpty()) throw OnlineException("This chapter came back empty.")
        return blocks
    }

    private fun spansOf(paragraph: Element): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        fun walk(node: Node, bold: Boolean, italic: Boolean) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText.replace(whitespaceRun, " ")
                    if (text.isNotEmpty()) spans += InlineSpan(text, bold = bold, italic = italic)
                }

                is Element -> when (node.tagName()) {
                    "script", "style", "iframe", "img" -> Unit
                    "br" -> spans += InlineSpan(" ")
                    "em", "i" -> node.childNodes().forEach { walk(it, bold, true) }
                    "strong", "b" -> node.childNodes().forEach { walk(it, true, italic) }
                    else -> node.childNodes().forEach { walk(it, bold, italic) }
                }
            }
        }
        paragraph.childNodes().forEach { walk(it, false, false) }
        return spans
    }

    /** Drops edge whitespace (the site indents every paragraph with a space). */
    private fun trimmed(spans: List<InlineSpan>): List<InlineSpan> {
        val out = spans.toMutableList()
        while (out.isNotEmpty() && out.first().text.isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().text.isBlank()) out.removeAt(out.lastIndex)
        if (out.isNotEmpty()) out[0] = out[0].copy(text = out[0].text.trimStart())
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(text = out.last().text.trimEnd())
        return out
    }
}
