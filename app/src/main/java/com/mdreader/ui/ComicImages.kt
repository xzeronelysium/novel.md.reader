package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.mdreader.data.comics.ComicCache
import com.mdreader.data.comics.ComicSource

/** A comic cover, decoded at shelf/detail size and cached like the novel covers. */
@Composable
fun rememberComicCover(source: ComicSource?, url: String?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var image by remember(url, source?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, source) {
        image = if (url.isNullOrEmpty() || source == null) {
            null
        } else {
            ComicCache.bitmap(context, source, url, maxWidth = 720)
        }
    }
    return image
}

/**
 * One page of a webtoon: full width, its own height, a quiet placeholder while it downloads.
 * Compose measures the image once decoded, so the strip grows as pages arrive.
 */
@Composable
fun ComicPage(
    source: ComicSource,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = ComicCache.bitmap(context, source, url, maxWidth = 1440)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (image == null) Modifier.height(280.dp) else Modifier)
            .background(ComicSkin.Panel),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = ComicSkin.Sub,
                strokeWidth = 1.5.dp,
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
