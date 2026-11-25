package com.mdreader.data.comics

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Weeb Central, hand-parsed with the selectors the Mihon extension uses. The site has no anti-bot
 * but the extension paces it at one request per two seconds; [gate] keeps that promise here.
 */
object WeebCentralSource : ComicSource {
    override val id = "weebcentral"
    override val name = "Weeb Central"
    override val origin = "https://weebcentral.com"

    private val gate = RateGate(2_000)

    private fun url(path: String) = if (path.startsWith("http")) path else origin + path

    override suspend fun search(query: String, page: Int): ComicSearchPage {
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val html = ComicHttp.get(
            "$origin/search/data?text=$term&limit=$PAGE_SIZE&offset=$offset&display_mode=Full+Display",
            gate = gate,
        )
        return ComicSearchPage(
            hits = WeebCentralParser.parseSearch(html),
            hasNext = WeebCentralParser.hasNextPage(html),
            page = page,
        )
    }

    override suspend fun details(path: String): ComicDetails =
        WeebCentralParser.parseDetails(ComicHttp.get(url(path), gate = gate))

    /** The list is served newest first; reading order is oldest first. */
    override suspend fun chapters(seriesPath: String): List<ComicChapter> =
        WeebCentralParser.parseChapters(
            // The endpoint lives on the id-only path: a slug there answers 200 with the 404 page.
            ComicHttp.get("$origin/series/${seriesId(seriesPath)}/full-chapter-list", headers = HX, gate = gate),
        ).asReversed()

    private fun seriesId(seriesPath: String): String =
        seriesPath.removePrefix("$origin").removePrefix("/series/").substringBefore('/')

    override suspend fun pages(chapterPath: String): List<String> =
        WeebCentralParser.parsePages(
            ComicHttp.get(
                "${url(chapterPath)}/images?is_prev=False&reading_style=long_strip",
                headers = HX,
                gate = gate,
            ),
        )

    override fun imageHeaders(url: String): Map<String, String> = mapOf("Referer" to "$origin/")

    /** Page and cover images live on the site and its two image hosts. */
    fun isImageUrl(url: String): Boolean = runCatching {
        val parsed = java.net.URL(url)
        parsed.protocol == "https" && when (parsed.host.lowercase()) {
            "weebcentral.com", "temp.compsci88.com", "planeptune.us" -> true
            else -> parsed.host.lowercase().endsWith(".planeptune.us")
        }
    }.getOrDefault(false)

    private const val PAGE_SIZE = 32
    private val HX = mapOf("HX-Request" to "true")
}

/** Pure functions, testable against saved pages like the novel parser. */
object WeebCentralParser {

    fun parseSearch(html: String): List<ComicHit> {
        val doc = Jsoup.parse(html, WeebCentralSource.origin)
        val seen = LinkedHashSet<String>()
        val hits = mutableListOf<ComicHit>()
        doc.select("a[href*=/series/]").forEach { link ->
            val href = link.attr("href")
            val path = sitePath(href) ?: return@forEach
            if (!seen.add(path)) return@forEach
            val title = link.selectFirst("div.text-ellipsis")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: link.selectFirst("img[alt]")?.attr("alt")?.removeSuffix(" cover")?.trim()
                ?: return@forEach
            val cover = link.selectFirst("source[srcset]")?.attr("srcset")
                ?.split(Regex("\\s+"))?.firstOrNull()?.replace("/cover/small/", "/cover/normal/")
                ?: link.selectFirst("img[src]")?.absUrl("src").orEmpty()
            hits += ComicHit(title = title, path = path, cover = cover)
        }
        return hits
    }

    fun hasNextPage(html: String): Boolean =
        Jsoup.parse(html, WeebCentralSource.origin).selectFirst("button[hx-get]") != null

    fun parseDetails(html: String): ComicDetails {
        val doc = Jsoup.parse(html, WeebCentralSource.origin)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) throw ComicException("That page doesn't look like a series page.")
        return ComicDetails(
            title = title,
            author = text(doc, "Author"),
            status = text(doc, "Status"),
            description = doc.selectFirst("li:has(strong:contains(Description)) p")?.text()?.trim() ?: "",
            genres = links(doc, "Tags"),
            cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
        )
    }

    /** Newest first, exactly as the site serves it. */
    fun parseChapters(html: String): List<ComicChapter> {
        val doc = Jsoup.parse(html, WeebCentralSource.origin)
        val seen = LinkedHashSet<String>()
        return doc.select("a[href^=/chapters/]").mapNotNull { link ->
            val path = sitePath(link.attr("href")) ?: return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null
            val title = link.selectFirst("span.grow > span")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: link.text().trim().ifEmpty { "Chapter" }
            val date = link.parent()?.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
                .ifEmpty { link.closest("div")?.selectFirst("time[datetime]")?.attr("datetime").orEmpty() }
            ComicChapter(title = title, path = path, date = date)
        }
    }

    fun parsePages(html: String): List<String> {
        val doc = Jsoup.parse(html, WeebCentralSource.origin)
        val images = doc.select("#chapter-images img").mapNotNull { img ->
            val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            src.takeIf { it.startsWith("https://") }
        }
        if (images.isEmpty()) throw ComicException("That chapter has no pages.")
        return images
    }

    /** Same-site path for links; anything else is refused. */
    private fun sitePath(href: String): String? = when {
        href.startsWith("${WeebCentralSource.origin}/") -> href.removePrefix(WeebCentralSource.origin)
        href.startsWith("/") && !href.startsWith("//") -> href
        else -> null
    }?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private fun text(doc: Document, label: String): String =
        doc.selectFirst("li:has(strong:contains($label)) a")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("li:has(strong:contains($label)) span")?.text()?.trim().orEmpty() }

    private fun links(doc: Document, label: String): List<String> =
        doc.select("li:has(strong:contains($label)) a").map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
