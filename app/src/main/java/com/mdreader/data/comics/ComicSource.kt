package com.mdreader.data.comics

/**
 * The small, in-process contract every comic site implements. It is deliberately narrower than
 * Mihon's HttpSource: the app has no plugin loader, so the two sites are plain objects here and
 * the reader only needs search, details, chapters and page images.
 */
interface ComicSource {
    /** Stable id saved on the shelf entry: "mangakakalot", "batcave". */
    val id: String
    val name: String
    /** Where the source lives, for Referer headers and host checks. */
    val origin: String

    suspend fun search(query: String, page: Int): ComicSearchPage
    suspend fun details(path: String): ComicDetails
    suspend fun chapters(seriesPath: String): List<ComicChapter>
    suspend fun pages(chapterPath: String): List<String>
    /** Headers a page/cover image needs, if any (a Referer, usually). */
    fun imageHeaders(url: String): Map<String, String> = emptyMap()

    /** Image bytes; sources behind a challenge override this to use their own HTTP stack. */
    suspend fun imageBytes(url: String): ByteArray? = ComicHttp.bytes(url, imageHeaders(url))
}

data class ComicHit(
    val title: String,
    /** Site path, e.g. `/series/01J.../Solo-Leveling`. */
    val path: String,
    val meta: String = "",
    val cover: String = "",
)

data class ComicSearchPage(
    val hits: List<ComicHit>,
    val hasNext: Boolean,
    val page: Int,
)

data class ComicDetails(
    val title: String,
    val author: String = "",
    val status: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val cover: String = "",
)

/** A chapter as the source lists it: its own title and the path the reader fetches. */
data class ComicChapter(
    val title: String,
    val path: String,
    /** ISO date from the site, or empty. */
    val date: String = "",
)

/** Thrown when a site asks for JavaScript before it will serve content. */
class ComicChallengeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Why a comic request produced no usable content. */
class ComicException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The hardcoded sources, in the order the Add dock offers them. */
object ComicSources {
    val all: List<ComicSource> = listOf(MangakakalotSource, BatCaveSource)

    fun byId(id: String?): ComicSource? = all.firstOrNull { it.id == id }

    /** True when a saved chapter path belongs to a comic (used when routing an old entry). */
    fun forProject(sourceId: String?, path: String?): ComicSource? =
        byId(sourceId) ?: all.firstOrNull { source ->
            path != null && (path.startsWith("${source.origin}/") || source.looksLikeItsPath(path))
        }
}

/** A source may recognise its own paths even without a saved id (older shelf entries). */
internal fun ComicSource.looksLikeItsPath(path: String): Boolean = when (id) {
    "mangakakalot" -> path.startsWith("/manga/") || path.startsWith("/chapter/")
    "batcave" -> path.startsWith("/comix/") || path.startsWith("/reader/")
    else -> false
}

/**
 * A cover URL owned by a comic source, or an inline data URI, routed to that source's image
 * pipeline. The shelf's cover cache is the novel's; this is its comic fallback, so comic covers
 * load on the shelf instead of dying in the novel fetcher.
 */
suspend fun comicCoverBytes(url: String): ByteArray? = when {
    url.startsWith("data:") -> ComicOkHttp.dataUriBytes(url)
    MangakakalotSource.isImageUrl(url) -> MangakakalotSource.imageBytes(url)
    url.startsWith(BatCaveSource.origin) -> BatCaveSource.imageBytes(url)
    else -> null
}
