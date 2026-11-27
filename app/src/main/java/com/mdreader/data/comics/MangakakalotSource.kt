package com.mdreader.data.comics

import org.json.JSONObject
import java.net.URLEncoder

/**
 * MangaKakalot, from its live home (mangakakalot.gg — the old .com is an ad redirect). Search
 * and series pages sit behind a managed Cloudflare challenge, so they go through the shared
 * [ChallengeSession]: one offscreen WebView clears it for the host, the honest way Mihon
 * does it. Chapters come from the site's own JSON API and the pages themselves from its
 * satellite reader (mangagoku.com), which serves plainly.
 */
object MangakakalotSource : ComicSource {
    override val id = "mangakakalot"
    override val name = "MangaKakalot"
    override val origin = "https://www.mangakakalot.gg"

    /** Where the chapters are actually read, served without a challenge. */
    const val SATELLITE_ORIGIN = "https://www.mangagoku.com"

    private val gate = RateGate(600)
    private val session = ChallengeSession(origin = origin, label = "MangaKakalot")

    private fun url(path: String) = if (path.startsWith("http")) path else origin + path

    override suspend fun search(query: String, page: Int): ComicSearchPage {
        // The engine's search path: words joined by underscores, pages as ?page=N.
        val term = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString("_") { URLEncoder.encode(it, "UTF-8") }
            .lowercase()
        val html = session.get(url("/search/story/$term" + if (page > 1) "?page=$page" else ""), gate)
        return ComicSearchPage(
            hits = MangakakalotParser.parseSearch(html, origin),
            hasNext = MangakakalotParser.hasNextPage(html, page),
            page = page,
        )
    }

    override suspend fun details(path: String): ComicDetails {
        val html = session.get(url(path), gate)
        return MangakakalotParser.parseDetails(html, origin)
    }

    /**
     * The series page loads its chapter list through the site's JSON API (the page itself ships
     * only an empty container). The API serves 50 chapters a page, so every page is walked
     * until its own pagination says there are no more — a 260-chapter manga is 260 chapters,
     * not the first fifty.
     */
    override suspend fun chapters(seriesPath: String): List<ComicChapter> {
        val slug = seriesPath.trimEnd('/').substringAfterLast('/')
        val all = LinkedHashSet<ComicChapter>()
        var offset = 0
        do {
            val json = session.get("$origin/api/manga/$slug/chapters?offset=$offset", gate)
            val page = MangakakalotParser.parseChapterPage(json, slug)
            all += page
            offset += page.size
        } while (page.isNotEmpty() && MangakakalotParser.hasMoreChapters(json))
        val chapters = all.toList()
        if (chapters.isEmpty()) throw ComicException("That manga has no chapters yet.")
        // The API answers newest first; reading order is oldest first.
        return chapters.asReversed()
    }

    override suspend fun pages(chapterPath: String): List<String> {
        val html = session.get(url(chapterPath), gate)
        return MangakakalotParser.parsePages(html)
    }

    override fun imageHeaders(url: String): Map<String, String> = mapOf("Referer" to "$origin/")

    override suspend fun imageBytes(url: String): ByteArray? =
        ComicOkHttp.bytes(url, imageHeaders(url))

    /** A cover from the site or its image host. */
    fun isImageUrl(url: String): Boolean = runCatching {
        val parsed = java.net.URL(url)
        parsed.protocol == "https" &&
            (parsed.host.equals(java.net.URI(origin).host, ignoreCase = true) ||
                parsed.host.endsWith(".2xstorage.com", ignoreCase = true))
    }.getOrDefault(false)
}

/**
 * Pure parsing for MangaKakalot, written against the live pages: search results in
 * panel_story_list, the info list on the series page, chapters from the site's JSON API,
 * and pages as the satellite reader's page images.
 */
object MangakakalotParser {

    fun parseSearch(html: String, origin: String): List<ComicHit> {
        val doc = org.jsoup.Jsoup.parse(html, origin)
        val seen = LinkedHashSet<String>()
        return doc.select(".panel_story_list .story_item").mapNotNull { item ->
            val link = item.selectFirst(".story_name a[href]") ?: return@mapNotNull null
            val path = sitePath(link.attr("href"), origin) ?: return@mapNotNull null
            if (!path.startsWith("/manga/") || !seen.add(path)) return@mapNotNull null
            ComicHit(
                title = link.text().trim(),
                path = path,
                cover = item.selectFirst("img[src], img[data-src]")?.attr("src").orEmpty(),
            )
        }
    }

    fun hasNextPage(html: String, page: Int): Boolean =
        org.jsoup.Jsoup.parse(html)
            .select(".group_page a[href*=page=${page + 1}]")
            .isNotEmpty()

    fun parseDetails(html: String, origin: String): ComicDetails {
        val doc = org.jsoup.Jsoup.parse(html, origin)
        val title = doc.selectFirst("ul.manga-info-text h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) throw ComicException("That page doesn't look like a manga page.")
        val infoLines = doc.select("ul.manga-info-text li").map { it.text().trim() }
        fun after(label: String) = infoLines.firstOrNull { it.startsWith(label, ignoreCase = true) }
            ?.substringAfter(':', "").orEmpty().trim()
        val summary = doc.selectFirst("#contentBox")?.apply { select("h2").remove() }
            ?.text()?.trim().orEmpty()
        return ComicDetails(
            title = title,
            author = after("Author(s)"),
            status = after("Status"),
            description = summary,
            genres = doc.select("ul.manga-info-text li.genres a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            cover = doc.selectFirst(".manga-info-pic img")?.attr("src").orEmpty(),
        )
    }

    /**
     * One page of the chapters API, newest first exactly as the site serves it.
     * [hasMoreChapters] reads the same answer's pagination.
     */
    fun parseChapterPage(json: String, slug: String): List<ComicChapter> {
        val data = runCatching { JSONObject(json).optJSONObject("data") }.getOrNull()
            ?: throw ComicException("That manga's chapter list came back in an unexpected format.")
        val chapters = data.optJSONArray("chapters")
            ?: throw ComicException("That manga has no chapters yet.")
        return (0 until chapters.length()).mapNotNull { i ->
            val item = chapters.getJSONObject(i)
            val chapterSlug = item.optString("chapter_slug")
            if (chapterSlug.isEmpty()) return@mapNotNull null
            ComicChapter(
                title = item.optString("chapter_name").ifEmpty { "Chapter ${item.optInt("chapter_num")}" },
                path = "${MangakakalotSource.SATELLITE_ORIGIN}/manga/$slug/$chapterSlug",
                date = item.optString("updated_at"),
            )
        }
    }

    fun hasMoreChapters(json: String): Boolean = runCatching {
        JSONObject(json).optJSONObject("data")?.optJSONObject("pagination")
            ?.optBoolean("has_more") == true
    }.getOrDefault(false)

    fun parsePages(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        // The reader's page images carry "... Chapter N page M - MangaGoku" alts; covers don't.
        val images = doc.select("img[alt*=\" page \"][src*=2xstorage.com]")
            .map { it.attr("src") }
            .filter { it.startsWith("https://") }
        if (images.isEmpty()) throw ComicException("That chapter has no pages.")
        return images
    }

    /** Same-site path for links; anything else is refused. */
    internal fun sitePath(href: String, origin: String): String? {
        val base = "$origin/"
        return when {
            href.startsWith(base) -> href.removePrefix(origin)
            href.startsWith("/") && !href.startsWith("//") -> href
            else -> null
        }?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    }
}
