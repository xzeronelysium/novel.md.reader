package com.mdreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import com.mdreader.data.comics.comicCoverBytes
import com.mdreader.data.online.FreeWebNovelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Decoded covers in memory, backed by files in the app cache so they survive restarts.
 * The memory cache is sized in bytes (an eighth of the heap) rather than in entries, and the
 * disk folder is pruned to a fixed budget, so a shelf of covers cannot grow without bound.
 */
internal object CoverCache {
    private const val MAX_WIDTH = 720
    private const val DISK_MAX_BYTES = 20L * 1024 * 1024

    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024)
    private val memory = object : LruCache<String, ImageBitmap>(maxBytes.toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    /** Blurred fields are expensive to build and identical for a given cover. */
    private val fields = java.util.WeakHashMap<ImageBitmap, ImageBitmap>()

    fun field(cover: ImageBitmap): ImageBitmap = synchronized(fields) {
        fields.getOrPut(cover) { cover.blurredField() }
    }

    private fun file(context: Context, url: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "covers").apply { mkdirs() }, name)
    }

    /** Decodes at most [MAX_WIDTH] wide: a 40 dp thumbnail does not need a full-size bitmap. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Keeps the cover folder under its budget, oldest files first. */
    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX_BYTES) return
        for (old in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX_BYTES * 3 / 4) break
            total -= old.length()
            old.delete()
        }
    }

    suspend fun load(context: Context, url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = file(context, url)
            val bytes = if (cached.isFile) {
                cached.readBytes()
            } else {
                (FreeWebNovelClient.loadCover(url) ?: comicCoverBytes(url))?.also { bytes ->
                    runCatching {
                        cached.writeBytes(bytes)
                        prune(cached.parentFile!!)
                    }
                }
            }
            bytes?.let(::decode)?.asImageBitmap()?.also { memory.put(url, it) }
        }
    }

    /** Drops a removed novel's cover from memory and disk. */
    suspend fun evict(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        memory.remove(url)
        withContext(Dispatchers.IO) { runCatching { file(context, url).delete() } }
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

private const val FIELD_WIDTH = 40

/**
 * A copy shrunk to [width] px wide in halving steps (one big jump would alias), which
 * stretches back up smoothly whatever the source's sharpness.
 */
private fun ImageBitmap.smoothed(width: Int): ImageBitmap {
    var bitmap = asAndroidBitmap()
    val targetHeight = (width * bitmap.height / bitmap.width).coerceAtLeast(1)
    while (bitmap.width / 2 > width) {
        bitmap = bitmap.scale(bitmap.width / 2, bitmap.height / 2)
    }
    return bitmap.scale(width, targetHeight).asImageBitmap()
}

/** [smoothed], then box-blurred three times, so stretching it to screen size shows no grid. */
private fun ImageBitmap.blurredField(): ImageBitmap {
    val base = smoothed(FIELD_WIDTH).asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val w = base.width
    val h = base.height
    var pixels = IntArray(w * h)
    base.getPixels(pixels, 0, w, 0, 0, w, h)
    repeat(3) { pixels = boxBlur(pixels, w, h, 3) }
    base.setPixels(pixels, 0, w, 0, 0, w, h)
    return base.asImageBitmap()
}

private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
    val span = 2 * radius + 1
    fun average(read: (Int) -> Int): Int {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = read(k)
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        return ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
    }
    val horizontal = IntArray(src.size)
    for (y in 0 until h) for (x in 0 until w) {
        horizontal[y * w + x] = average { k -> src[y * w + (x + k).coerceIn(0, w - 1)] }
    }
    val out = IntArray(src.size)
    for (y in 0 until h) for (x in 0 until w) {
        out[y * w + x] = average { k -> horizontal[(y + k).coerceIn(0, h - 1) * w + x] }
    }
    return out
}

private val Saturated = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.25f) })

/** Where a [coverField] is strongest and how it dissolves. */
enum class FieldFade { Home, Detail, Page }

/**
 * A field of the cover's colours behind a section, as blurred as frosted glass would make it, so
 * the cover's own sharpness never shows. It dissolves at its edges instead of ending on a line.
 */
@Composable
fun Modifier.coverField(cover: ImageBitmap?, fade: FieldFade, alpha: Float = 0.75f): Modifier {
    if (cover == null) return this
    val field = remember(cover) { CoverCache.field(cover) }
    val stops = when (fade) {
        // Fades in above the card, holds behind it, fades out into the shelf bar.
        FieldFade.Home -> arrayOf(
            0f to Color.Transparent,
            0.2f to Color.Black,
            0.55f to Color.Black,
            1f to Color.Transparent,
        )
        // Strongest at the top, gone before the button.
        FieldFade.Detail -> arrayOf(
            0f to Color.Black,
            0.3f to Color.Black,
            0.62f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Transparent,
        )
        // A long, soft wash over a whole page section.
        FieldFade.Page -> arrayOf(
            0f to Color.Black,
            0.3f to Color.Black,
            0.65f to Color.Black.copy(alpha = 0.6f),
            1f to Color.Transparent,
        )
    }
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawCropped(field, alpha = alpha, colorFilter = Saturated)
            drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
            drawContent()
        }
}

/** The pane that sits on a [coverField]: square ink border, a darker tint, no sharp cover. */
@Composable
fun Modifier.glassPane(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return this
        .background(scheme.background.copy(alpha = 0.38f))
        .border(1.dp, scheme.onSurface.copy(alpha = 0.55f))
}
