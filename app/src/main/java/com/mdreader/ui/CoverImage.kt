package com.mdreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import com.mdreader.data.online.FreeWebNovelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Decoded covers in memory, backed by files in the app cache so they survive restarts. */
private object CoverCache {
    private val memory = object : LruCache<String, ImageBitmap>(12) {}

    private fun file(context: Context, url: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "covers").apply { mkdirs() }, name)
    }

    suspend fun load(context: Context, url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = file(context, url)
            val bytes = if (cached.isFile) {
                cached.readBytes()
            } else {
                FreeWebNovelClient.loadCover(url)?.also { runCatching { cached.writeBytes(it) } }
            }
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
                ?.also { memory.put(url, it) }
        }
    }
}

/** The novel's cover, or null while it loads, when it has none, or if it can't be fetched. */
@Composable
fun rememberCover(url: String?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = if (url.isNullOrEmpty()) null else CoverCache.load(context, url)
    }
    return image
}

private fun DrawScope.drawCropped(
    image: ImageBitmap,
    alpha: Float,
    colorFilter: ColorFilter? = null,
) {
    val dstAspect = size.width / size.height
    val srcAspect = image.width.toFloat() / image.height
    val srcW: Int
    val srcH: Int
    if (dstAspect > srcAspect) {
        srcW = image.width
        srcH = (image.width / dstAspect).toInt().coerceIn(1, image.height)
    } else {
        srcH = image.height
        srcW = (image.height * dstAspect).toInt().coerceIn(1, image.width)
    }
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.High,
    )
}

private const val BACKDROP_WIDTH = 28
private const val GLOW_WIDTH = 12

/**
 * A copy shrunk to [width] px wide in halving steps (one big jump would alias), which
 * stretches back up as a smooth blur whatever the source's sharpness.
 */
private fun ImageBitmap.smoothed(width: Int): ImageBitmap {
    var bitmap = asAndroidBitmap()
    val targetHeight = (width * bitmap.height / bitmap.width).coerceAtLeast(1)
    while (bitmap.width / 2 > width) {
        bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
    }
    return Bitmap.createScaledBitmap(bitmap, width, targetHeight, true).asImageBitmap()
}

private val InkOnly = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * The cover as black-and-white ink behind the content, strongest at the top and
 * dissolving to nothing at the bottom edge, so it never ends on a line.
 */
@Composable
fun Modifier.coverBackdrop(cover: ImageBitmap?, page: Color): Modifier {
    if (cover == null) return this
    val soft = remember(cover) { cover.smoothed(BACKDROP_WIDTH) }
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawCropped(soft, alpha = 0.6f, colorFilter = InkOnly)
            drawRect(
                Brush.verticalGradient(
                    0f to page.copy(alpha = 0.2f),
                    0.55f to page.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                ),
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black,
                    0.35f to Color.Black.copy(alpha = 0.7f),
                    0.7f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
            drawContent()
        }
}

/**
 * A soft wash of the cover's colours behind a whole page section. A tiny copy stretched
 * with smoothing looks blurred on every Android version, unlike a blur effect (12+ only).
 */
@Composable
fun Modifier.coverGlow(cover: ImageBitmap?): Modifier {
    if (cover == null) return this
    val tiny = remember(cover) { cover.smoothed(GLOW_WIDTH) }
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawCropped(tiny, alpha = 0.42f)
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black,
                    0.3f to Color.Black,
                    0.65f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
            drawContent()
        }
}
