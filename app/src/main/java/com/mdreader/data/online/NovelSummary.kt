package com.mdreader.data.online

import com.mdreader.data.Project

/** The detail page's header from what the shelf already knows, so it never waits for a request. */
fun Project.asDetails(): NovelDetails = NovelDetails(
    title = name,
    author = author,
    status = status,
    genres = genres,
    synopsis = synopsis,
    totalPages = 1,
    cover = coverUrl.orEmpty(),
    authorPath = authorPath,
    rating = rating,
    votes = votes,
)
