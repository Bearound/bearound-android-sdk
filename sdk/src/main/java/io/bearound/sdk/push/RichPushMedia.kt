package io.bearound.sdk.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MEDIA_TAG = "BeAroundSDK-RichPush"

/**
 * How a bitmap is prepared before it goes into a notification: center-cropped to the box it
 * is shown in ([aspect], width / height; null keeps the source aspect), then downscaled to fit
 * [maxWidth] x [maxHeight] (never upscaled). Everything a RemoteViews carries is parceled to
 * System UI and counted against the platform's RemoteViews size limit, so the bitmap is made
 * exactly as big as the box needs and no bigger.
 */
internal data class RichImageSpec(
    val maxWidth: Int,
    val maxHeight: Int,
    val aspect: Float?,
    val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    /** Part of the cache key: the same media prepared for two different boxes never collides. */
    val variant: String
        get() = buildString {
            append(maxWidth).append('x').append(maxHeight)
            aspect?.let { append("a").append((it * 100).roundToInt()) }
            if (config == Bitmap.Config.RGB_565) append("_565")
        }

    val bytesPerPixel: Int
        get() = if (config == Bitmap.Config.RGB_565) 2 else 4
}

/** Source crop rectangle and output size computed by [RichBitmaps.plan]. */
internal data class RichFit(
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val outWidth: Int,
    val outHeight: Int
)

/** Pure bitmap math plus the one Canvas step that applies it. */
internal object RichBitmaps {

    /** Crop + downscale plan for a [srcWidth] x [srcHeight] source. Null for an empty source. */
    fun plan(srcWidth: Int, srcHeight: Int, spec: RichImageSpec): RichFit? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        var cropWidth = srcWidth
        var cropHeight = srcHeight
        spec.aspect?.takeIf { it > 0f }?.let { aspect ->
            if (srcWidth.toFloat() / srcHeight > aspect) {
                cropWidth = (srcHeight * aspect).roundToInt().coerceIn(1, srcWidth)
            } else {
                cropHeight = (srcWidth / aspect).roundToInt().coerceIn(1, srcHeight)
            }
        }
        val scale = minOf(1f, spec.maxWidth.toFloat() / cropWidth, spec.maxHeight.toFloat() / cropHeight)
        return RichFit(
            cropLeft = (srcWidth - cropWidth) / 2,
            cropTop = (srcHeight - cropHeight) / 2,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            outWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1),
            outHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /** Largest power-of-two decode sample that still leaves the crop at least as big as the output. */
    fun sampleSize(fit: RichFit): Int {
        var sample = 1
        while (fit.cropWidth / (sample * 2) >= fit.outWidth && fit.cropHeight / (sample * 2) >= fit.outHeight) {
            sample *= 2
        }
        return sample
    }

    /**
     * Frame spec that keeps [count] frames of [aspect] within [budgetBytes] in total, capped at
     * [maxWidth]: the frames of one ViewFlipper all live in the same RemoteViews.
     */
    fun frameSpec(
        budgetBytes: Long,
        count: Int,
        aspect: Float,
        maxWidth: Int,
        config: Bitmap.Config
    ): RichImageSpec {
        val bytesPerPixel = if (config == Bitmap.Config.RGB_565) 2 else 4
        val pixelsPerFrame = budgetBytes / count.coerceAtLeast(1) / bytesPerPixel
        val width = minOf(maxWidth, floor(sqrt(pixelsPerFrame * aspect.toDouble())).toInt()).coerceAtLeast(16)
        val height = floor(width / aspect.toDouble()).toInt().coerceAtLeast(9)
        return RichImageSpec(width, height, aspect, config)
    }

    /** Applies [spec] to [source]. Returns [source] itself when it already matches. */
    fun render(source: Bitmap, spec: RichImageSpec): Bitmap? {
        val fit = plan(source.width, source.height, spec) ?: return null
        if (fit.cropLeft == 0 && fit.cropTop == 0 &&
            fit.outWidth == source.width && fit.outHeight == source.height &&
            source.config == spec.config
        ) return source
        val out = createBitmap(fit.outWidth, fit.outHeight, spec.config)
        Canvas(out).drawBitmap(
            source,
            Rect(fit.cropLeft, fit.cropTop, fit.cropLeft + fit.cropWidth, fit.cropTop + fit.cropHeight),
            Rect(0, 0, fit.outWidth, fit.outHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /** Decodes [bytes] with the smallest sample that still satisfies [spec], then applies it. */
    fun decode(bytes: ByteArray, spec: RichImageSpec): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val fit = plan(bounds.outWidth, bounds.outHeight, spec) ?: return null
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(fit) }
        ) ?: return null
        return render(decoded, spec)
    }

    /**
     * Bitmap memory a RemoteViews carries for [bitmaps]: each distinct instance counts once,
     * because its bitmap cache dedups by identity (a frame reused in two slots costs nothing).
     */
    fun remoteViewsBytes(bitmaps: Iterable<Bitmap?>): Long {
        val distinct = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        bitmaps.forEach { if (it != null) distinct += it }
        return distinct.sumOf { it.allocationByteCount.toLong() }
    }
}

/** Evenly spaced frame times for the animated preview of a PLAY push. */
internal object RichFrameTimes {
    /**
     * [count] timestamps (microseconds) at the middle of [count] equal slices of the video, so
     * the preview never starts on the (often black) first frame nor ends on the last one.
     * An unknown duration yields the first frame only.
     */
    fun timesUs(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return listOf(0L)
        val durationUs = durationMs * 1_000
        return (0 until count).map { i -> durationUs * (2 * i + 1) / (2L * count) }
    }

    /** Frame shown in flipper slot [slot] of [slots] when only [frames] frames exist (repeats keep the pace even). */
    fun frameForSlot(slot: Int, slots: Int, frames: Int): Int {
        if (frames <= 0 || slots <= 0) return 0
        return (slot * frames / slots).coerceIn(0, frames - 1)
    }
}

/**
 * Two-level cache of prepared notification media: an in-memory LRU for the running process
 * and files in `cacheDir` that survive it (a carousel page turn often lands in a fresh
 * process). Files are keyed by a hash of the media URL, never by the tracker URL.
 */
internal object RichMediaCache {
    private const val DIR_NAME = "bearound_rich_push"
    private const val MAX_FILE_AGE_MS = 48L * 60 * 60 * 1000

    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    fun urlHash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun key(url: String, spec: RichImageSpec): String = urlHash(url) + "_" + spec.variant

    fun videoFile(dir: File, url: String): File = File(dir, urlHash(url) + ".mp4")

    /** Memory first, then disk (promoted back to memory). Null on a miss. */
    fun get(dir: File, url: String, spec: RichImageSpec): Bitmap? {
        val key = key(url, spec)
        memory.get(key)?.let { return it }
        val file = File(dir, "$key.img")
        if (!file.isFile) return null
        val bitmap = try {
            BitmapFactory.decodeFile(
                file.path, BitmapFactory.Options().apply { inPreferredConfig = spec.config }
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    fun put(dir: File, url: String, spec: RichImageSpec, bitmap: Bitmap) {
        val key = key(url, spec)
        memory.put(key, bitmap)
        try {
            // Parallel card loads race here: mkdirs() is false for the loser, so check the result.
            if (!dir.isDirectory) dir.mkdirs()
            if (!dir.isDirectory) return
            val tmp = File(dir, "$key.tmp-${Thread.currentThread().id}")
            FileOutputStream(tmp).use { out ->
                if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                else bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (!tmp.renameTo(File(dir, "$key.img"))) tmp.delete()
        } catch (t: Throwable) {
            Log.w(MEDIA_TAG, "Media cache write failed: ${t.message}")
        }
    }

    /** Drops files older than two days. Cheap: one directory listing per push. */
    fun prune(dir: File, nowMs: Long = System.currentTimeMillis()) {
        try {
            dir.listFiles()?.forEach { if (nowMs - it.lastModified() > MAX_FILE_AGE_MS) it.delete() }
        } catch (_: Throwable) {
        }
    }

    fun clearMemory() = memory.evictAll()

    fun clearDisk(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Fetches and prepares one image. Abstracted so tests never hit the network. */
internal fun interface RichImageLoader {
    fun load(url: String, spec: RichImageSpec): Bitmap?
}

/** Default loader: short-timeout GET, capped body size, sampled decode, then [RichBitmaps.render]. */
internal object HttpRichImageLoader : RichImageLoader {
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val MAX_BYTES = 5 * 1024 * 1024

    override fun load(url: String, spec: RichImageSpec): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                Log.w(MEDIA_TAG, "Image fetch failed (HTTP ${connection.responseCode})")
                return null
            }
            val bytes = connection.inputStream.use { readCapped(it) } ?: return null
            RichBitmaps.decode(bytes, spec)
        } catch (t: Throwable) {
            Log.w(MEDIA_TAG, "Image fetch failed: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** Produces the preview frames of a PLAY push. Abstracted so tests never hit the network. */
internal fun interface RichVideoFrameSource {
    /** Up to [count] frames of the video at [url], prepared with [spec]; empty on any failure. */
    fun frames(context: Context, url: String, count: Int, spec: RichImageSpec, deadlineMs: Long): List<Bitmap>
}

/**
 * Default frame source: downloads the MP4 into the media cache (the player activity reuses
 * the file) and pulls evenly spaced frames with [MediaMetadataRetriever]. Framework only.
 */
internal object HttpRichVideoFrameSource : RichVideoFrameSource {
    internal const val MAX_VIDEO_BYTES = 15L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 5_000

    override fun frames(
        context: Context,
        url: String,
        count: Int,
        spec: RichImageSpec,
        deadlineMs: Long
    ): List<Bitmap> {
        val file = download(RichMediaCache.dir(context), url, deadlineMs) ?: return emptyList()
        return extract(file, count, spec, deadlineMs)
    }

    /** The cached file when present, else a capped download. Null on failure, oversize or timeout. */
    internal fun download(dir: File, url: String, deadlineMs: Long): File? {
        val target = RichMediaCache.videoFile(dir, url)
        if (target.isFile && target.length() > 0) return target
        if (!dir.isDirectory) dir.mkdirs()
        if (!dir.isDirectory) return null
        val tmp = File(dir, target.name + ".tmp-${Thread.currentThread().id}")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                Log.w(MEDIA_TAG, "Video fetch failed (HTTP ${connection.responseCode})")
                return null
            }
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_VIDEO_BYTES) {
                Log.w(MEDIA_TAG, "Video skipped: $declared bytes is over the limit")
                return null
            }
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO_BYTES) {
                            Log.w(MEDIA_TAG, "Video skipped: over the size limit")
                            return null
                        }
                        if (System.currentTimeMillis() > deadlineMs) {
                            Log.w(MEDIA_TAG, "Video skipped: download over the time budget")
                            return null
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (total == 0L || !tmp.renameTo(target)) return null
            return target
        } catch (t: Throwable) {
            Log.w(MEDIA_TAG, "Video fetch failed: ${t.message}")
            return null
        } finally {
            connection?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun extract(file: File, count: Int, spec: RichImageSpec, deadlineMs: Long): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(file.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fit = RichBitmaps.plan(width, height, spec)
            for (timeUs in RichFrameTimes.timesUs(durationMs, count)) {
                if (System.currentTimeMillis() > deadlineMs) break
                // CLOSEST (not CLOSEST_SYNC): sync frames are seconds apart and would repeat.
                val raw = if (Build.VERSION.SDK_INT >= 27 && fit != null && rotation % 180 == 0) {
                    val scale = maxOf(fit.outWidth.toFloat() / fit.cropWidth, fit.outHeight.toFloat() / fit.cropHeight)
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1)
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue
                val frame = RichBitmaps.render(raw, spec) ?: continue
                if (frame !== raw) raw.recycle()
                frames += frame
            }
        } catch (t: Throwable) {
            Log.w(MEDIA_TAG, "Video frame extraction failed: ${t.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        return frames
    }
}

/** Fire-and-forget tracker hit. Abstracted so tests can capture it. */
internal fun interface RichHitSender {
    fun send(url: String)
}

/**
 * Default sender: one GET on a background thread that does NOT follow the redirect (the
 * tracker records the hit on the request itself; the target is opened by the SDK, not here).
 */
internal object HttpRichHitSender : RichHitSender {
    private const val TIMEOUT_MS = 5_000

    override fun send(url: String) {
        thread(name = "bearound-rich-hit") {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                }
                Log.d(MEDIA_TAG, "Tracker hit sent (HTTP ${connection.responseCode})")
            } catch (t: Throwable) {
                Log.w(MEDIA_TAG, "Tracker hit failed: ${t.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
