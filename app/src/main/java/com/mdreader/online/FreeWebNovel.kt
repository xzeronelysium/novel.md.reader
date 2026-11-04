package com.mdreader.online

import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.URLEncoder

data class OnlineNovel(
    val title: String,
    val url: String,
    val coverUrl: String? = null,
)

data class OnlineChapter(
    val title: String,
    val url: String,
)

data class OnlineBook(
    val title: String,
    val url: String,
    val chapters: List<OnlineChapter>,
)

class FreeWebNovelException(message: String) : IOException(message)

class FreeWebNovelClient(
    private val http: HttpClient = defaultClient(),
) : AutoCloseable {
    suspend fun search(query: String): List<OnlineNovel> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        return FreeWebNovelParser.search(get("${ORIGIN}search?keyword=$encoded&page=1"))
    }

    suspend fun loadBook(url: String): OnlineBook {
        val bookUrl = FreeWebNovelParser.bookUrl(url)
            ?: throw FreeWebNovelException("That novel link is not on Free Web Novel.")
        val page = get(bookUrl)
        val listed = FreeWebNovelParser.pagination(page)
        val first = chapterPage(bookUrl, 1, listed.pageSize)
        val totalPages = maxOf(listed.totalPages, first.totalPages).coerceIn(1, MAX_PAGES)
        val pageSize = first.pageSize.takeIf { it in 1..100 } ?: listed.pageSize
        if (first.page != 1) {
            throw FreeWebNovelException("Chapter list page 1 came back as page ${first.page}.")
        }

        val pages = coroutineScope {
            val gate = Semaphore(2)
            (1..totalPages).map { pageNumber ->
                async {
                    gate.withPermit {
                        delay(120)
                        pageNumber to if (pageNumber == 1) {
                            first
                        } else {
                            chapterPage(bookUrl, pageNumber, pageSize)
                        }
                    }
                }
            }.awaitAll()
        }.sortedBy { it.first }

        val chapters = mutableListOf<OnlineChapter>()
        val seen = mutableSetOf<String>()
        pages.forEach { (requested, result) ->
            if (result.page != requested) {
                throw FreeWebNovelException(
                    "Chapter list page $requested came back as page ${result.page}.",
                )
            }
            result.chapters.forEach { chapter ->
                if (seen.add(chapter.url)) chapters += chapter
            }
        }
        if (chapters.isEmpty()) {
            throw FreeWebNovelException("No chapters were listed for this novel.")
        }
        return OnlineBook(
            title = FreeWebNovelParser.title(page),
            url = bookUrl,
            chapters = chapters,
        )
    }

    suspend fun readChapter(url: String): List<Block> {
        val chapterUrl = FreeWebNovelParser.chapterUrl(url)
            ?: throw FreeWebNovelException("That chapter link is not on Free Web Novel.")
        return FreeWebNovelParser.chapter(get(chapterUrl))
    }

    override fun close() {
        http.close()
    }

    private suspend fun chapterPage(bookUrl: String, page: Int, pageSize: Int): ChapterPage {
        val response = get(
            "$bookUrl?ajax=chapters&page=$page&pageSize=$pageSize",
        )
        return FreeWebNovelParser.chapterPage(response)
    }

    private suspend fun get(url: String): String {
        val response = http.get(url) {
            header(HttpHeaders.Accept, "text/html,application/json;q=0.9,*/*;q=0.8")
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw FreeWebNovelException(statusMessage(response))
        }
        if (body.contains("cf-browser-verification", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true) &&
            body.contains("cloudflare", ignoreCase = true)
        ) {
            throw FreeWebNovelException("Free Web Novel asked for a browser check this app does not complete.")
        }
        return body
    }

    private fun statusMessage(response: HttpResponse): String = when (response.status.value) {
        429 -> "Free Web Novel is limiting requests. Wait a moment and try again."
        403 -> "Free Web Novel refused the request."
        else -> "Free Web Novel returned ${response.status.value}."
    }

    companion object {
        const val ORIGIN = "https://freewebnovel.com/"
        private const val MAX_PAGES = 200
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

        private fun defaultClient(): HttpClient = HttpClient(Android) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 25_000
                socketTimeoutMillis = 25_000
            }
        }
    }
}

internal data class ChapterPage(
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val chapters: List<OnlineChapter>,
)

internal data class Pagination(
    val totalPages: Int,
    val pageSize: Int,
)

internal object FreeWebNovelParser {
    fun search(html: String): List<OnlineNovel> {
        val document = Jsoup.parse(html, FreeWebNovelClient.ORIGIN)
        return document.select("h3.tit a[href]")
            .mapNotNull { link ->
                val title = link.text().trim()
                val url = bookUrl(link.attr("href")) ?: return@mapNotNull null
                if (title.isBlank()) null else OnlineNovel(title, url)
            }
            .distinctBy { it.url }
    }

    fun title(html: String): String {
        val document = Jsoup.parse(html, FreeWebNovelClient.ORIGIN)
        return document.selectFirst("h1.tit")?.text()?.trim()?.ifBlank { null }
            ?: document.title().substringBefore("|").trim().ifBlank { "Untitled" }
    }

    fun pagination(html: String): Pagination {
        val node = Jsoup.parse(html, FreeWebNovelClient.ORIGIN).selectFirst("#indexListPage")
        return Pagination(
            totalPages = node?.attr("data-total-page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            pageSize = node?.attr("data-page-size")?.toIntOrNull()?.coerceIn(1, 100) ?: 40,
        )
    }

    fun chapterPage(body: String): ChapterPage {
        val json = JSONObject(body)
        val code = json.optInt("code", 200)
        if (code !in 200..299) {
            throw FreeWebNovelException("Free Web Novel returned chapter-list code $code.")
        }
        val document = Jsoup.parse(json.optString("html"), FreeWebNovelClient.ORIGIN)
        val chapters = document.select("a[href]").mapNotNull { link ->
            val url = chapterUrl(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").trim().ifBlank { link.text().trim() }.ifBlank { "Chapter" }
            OnlineChapter(title, url)
        }
        return ChapterPage(
            page = json.optInt("page", 0),
            pageSize = json.optInt("pageSize", 40).coerceIn(1, 100),
            totalPages = json.optInt("totalPage", 1).coerceAtLeast(1),
            chapters = chapters,
        )
    }

    fun chapter(html: String): List<Block> {
        val document = Jsoup.parse(html, FreeWebNovelClient.ORIGIN)
        val article = document.selectFirst("#article")
            ?: document.selectFirst(".m-read .txt")
            ?: document.selectFirst(".txt")
            ?: return emptyList()
        val children = article.children()
        val ordered = if (children.any { it.hasAttr("data-reader-order-index") }) {
            children.sortedBy { it.attr("data-reader-order-index").toIntOrNull() ?: Int.MAX_VALUE }
        } else {
            children
        }
        return ordered.flatMap { block(it) }
    }

    fun bookUrl(value: String): String? = absolute(value)?.takeIf { "/chapter-" !in it }

    fun chapterUrl(value: String): String? = absolute(value)?.takeIf { "/chapter-" in it }

    private fun absolute(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        val withHost = when {
            trimmed.startsWith(FreeWebNovelClient.ORIGIN) -> trimmed
            trimmed.startsWith("/") -> FreeWebNovelClient.ORIGIN.trimEnd('/') + trimmed
            else -> return null
        }
        return withHost.substringBefore("#").substringBefore("?").trimEnd('/')
            .takeIf { it.startsWith(FreeWebNovelClient.ORIGIN) }
    }

    private fun block(element: Element): List<Block> {
        if (skip(element)) return emptyList()
        return when (element.normalName()) {
            "p" -> paragraph(element)
            "h1", "h2", "h3", "h4" -> {
                val text = element.text().trim()
                if (text.isEmpty()) emptyList() else listOf(Block.Heading(element.normalName().drop(1).toInt(), text))
            }
            "div", "section", "article" -> element.children().flatMap { block(it) }
            else -> emptyList()
        }
    }

    private fun paragraph(element: Element): List<Block> {
        val text = spans(element).joinToString("") { it.text }.trim()
        if (text.isEmpty()) return emptyList()
        return listOf(Block.Paragraph(trimSpans(spans(element))))
    }

    private fun spans(node: Node, bold: Boolean = false, italic: Boolean = false): List<InlineSpan> {
        return when (node) {
            is TextNode -> {
                val text = node.text().replace('\u00a0', ' ')
                if (text.isEmpty()) emptyList() else listOf(InlineSpan(text, bold, italic))
            }
            is Element -> {
                if (skip(node)) return emptyList()
                val name = node.normalName()
                val nextBold = bold || name == "strong" || name == "b"
                val nextItalic = italic || name == "em" || name == "i"
                node.childNodes().flatMap { spans(it, nextBold, nextItalic) }
            }
            else -> emptyList()
        }
    }

    private fun trimSpans(spans: List<InlineSpan>): List<InlineSpan> {
        if (spans.isEmpty()) return spans
        val copy = spans.map { it.copy(text = it.text.replace(Regex("[\\t\\r\\n]+"), " ")) }.toMutableList()
        copy[0] = copy[0].copy(text = copy[0].text.trimStart())
        copy[copy.lastIndex] = copy[copy.lastIndex].copy(text = copy[copy.lastIndex].text.trimEnd())
        return copy.filter { it.text.isNotEmpty() }
    }

    private fun skip(element: Element): Boolean {
        val name = element.normalName()
        if (name in setOf("script", "style", "iframe", "ins", "noscript")) return true
        val classes = element.className().lowercase()
        return "reader-ad" in classes || "ads" in classes || "unlock-buttons" in classes
    }
}
