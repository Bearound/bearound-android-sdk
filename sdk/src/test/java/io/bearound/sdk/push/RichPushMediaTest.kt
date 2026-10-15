package io.bearound.sdk.push

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

/** Pure parts of the rich push media pipeline: frame times, downscale math, cache keys and hits. */
@RunWith(RobolectricTestRunner::class)
class RichPushMediaTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rich-media").toFile()
        RichMediaCache.clearMemory()
    }

    @After
    fun tearDown() {
        RichMediaCache.clearMemory()
        dir.deleteRecursively()
    }

    // region frame times

    @Test
    fun `frame times sit in the middle of equal slices of the video`() {
        assertEquals(
            listOf(625_000L, 1_875_000L, 3_125_000L, 4_375_000L, 5_625_000L, 6_875_000L, 8_125_000L, 9_375_000L),
            RichFrameTimes.timesUs(10_000, 8)
        )
        assertEquals(listOf(1_500_000L), RichFrameTimes.timesUs(3_000, 1))
    }

    @Test
    fun `unknown duration yields the first frame only`() {
        assertEquals(listOf(0L), RichFrameTimes.timesUs(0, 8))
        assertEquals(listOf(0L), RichFrameTimes.timesUs(-1, 8))
    }

    @Test
    fun `fewer frames than slots repeat evenly`() {
        assertEquals((0 until 8).toList(), (0 until 8).map { RichFrameTimes.frameForSlot(it, 8, 8) })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), (0 until 8).map { RichFrameTimes.frameForSlot(it, 8, 4) })
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2), (0 until 8).map { RichFrameTimes.frameForSlot(it, 8, 3) })
    }

    // endregion

    // region downscale math

    @Test
    fun `carousel card is cropped to its box and capped at 720 px wide`() {
        val fit = RichBitmaps.plan(1080, 1080, RichNotificationBuilder.CAROUSEL_SPEC)!!
        assertEquals(1080, fit.cropWidth)
        assertEquals(568, fit.cropHeight) // 1080 / 1.9
        assertEquals(256, fit.cropTop)
        assertEquals(720, fit.outWidth)
        assertEquals(379, fit.outHeight)
        // Was 720 x 720 ARGB (2,073,600 B, over the platform's 2 MB RemoteViews warning).
        assertTrue(fit.outWidth * fit.outHeight * 4 < 1_100_000)
    }

    @Test
    fun `image keeps its aspect and is capped at 1080 px on both edges`() {
        val wide = RichBitmaps.plan(4000, 2000, RichNotificationBuilder.IMAGE_SPEC)!!
        assertEquals(1080 to 540, wide.outWidth to wide.outHeight)
        val tall = RichBitmaps.plan(1000, 3000, RichNotificationBuilder.IMAGE_SPEC)!!
        assertEquals(360 to 1080, tall.outWidth to tall.outHeight)
    }

    @Test
    fun `small sources are never upscaled`() {
        val fit = RichBitmaps.plan(300, 200, RichNotificationBuilder.CAROUSEL_SPEC)!!
        assertEquals(300, fit.cropWidth)
        assertEquals(158, fit.cropHeight)
        assertEquals(300 to 158, fit.outWidth to fit.outHeight)
        assertNull(RichBitmaps.plan(0, 100, RichNotificationBuilder.CAROUSEL_SPEC))
    }

    @Test
    fun `decode sample keeps the crop at least as big as the output`() {
        val fit = RichBitmaps.plan(4000, 3000, RichNotificationBuilder.CAROUSEL_SPEC)!!
        val sample = RichBitmaps.sampleSize(fit)
        assertEquals(4, sample)
        assertTrue(fit.cropWidth / sample >= fit.outWidth)
        assertTrue(fit.cropHeight / sample >= fit.outHeight)
        assertEquals(1, RichBitmaps.sampleSize(RichBitmaps.plan(1080, 1080, RichNotificationBuilder.CAROUSEL_SPEC)!!))
    }

    @Test
    fun `eight video frames fit the RemoteViews budget`() {
        val spec = RichNotificationBuilder.FRAME_SPEC
        assertEquals(Bitmap.Config.RGB_565, spec.config)
        assertEquals(447, spec.maxWidth)
        assertEquals(251, spec.maxHeight)
        // A 640 x 360 source, as measured on a device: 446 x 251 x 2 B x 8 = 1,791,136 B.
        val fit = RichBitmaps.plan(640, 360, spec)!!
        val total = fit.outWidth.toLong() * fit.outHeight * spec.bytesPerPixel * RichNotificationBuilder.FRAME_COUNT
        assertEquals(1_791_136L, total)
        assertTrue(total <= RichNotificationBuilder.FRAME_BUDGET_BYTES)
        // A 1080p source lands on the same box.
        val hd = RichBitmaps.plan(1920, 1080, spec)!!
        assertTrue(hd.outWidth <= spec.maxWidth && hd.outHeight <= spec.maxHeight)
    }

    @Test
    fun `render crops, scales and converts in one step`() {
        val source = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val out = RichBitmaps.render(source, RichNotificationBuilder.CAROUSEL_SPEC)!!
        assertEquals(720 to 379, out.width to out.height)
        val frame = RichBitmaps.render(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888), RichNotificationBuilder.FRAME_SPEC)!!
        assertEquals(Bitmap.Config.RGB_565, frame.config)
        val exact = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertSame("already fits: no copy", exact, RichBitmaps.render(exact, RichImageSpec(200, 200, 1f)))
    }

    @Test
    fun `RemoteViews bytes count each distinct bitmap once`() {
        val a = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565)
        assertEquals(400L + 200L, RichBitmaps.remoteViewsBytes(listOf(a, a, b, null, b)))
    }

    // endregion

    // region cache

    @Test
    fun `cache key is a stable url hash plus the prepared variant`() {
        val url = "https://api.example.com/push-media/abc"
        val key = RichMediaCache.key(url, RichNotificationBuilder.CAROUSEL_SPEC)
        assertEquals(key, RichMediaCache.key(url, RichNotificationBuilder.CAROUSEL_SPEC))
        assertTrue(key, key.matches(Regex("^[0-9a-f]{64}_720x380a190$")))
        assertNotEquals(key, RichMediaCache.key("$url/other", RichNotificationBuilder.CAROUSEL_SPEC))
        assertNotEquals(key, RichMediaCache.key(url, RichNotificationBuilder.CARD_SPEC))
        assertTrue(RichMediaCache.key(url, RichNotificationBuilder.FRAME_SPEC).endsWith("_565"))
        assertEquals(RichMediaCache.urlHash(url) + ".mp4", RichMediaCache.videoFile(dir, url).name)
    }

    @Test
    fun `cache hits memory first, then the file after the process memory is gone`() {
        val url = "https://api.example.com/push-media/card"
        val spec = RichNotificationBuilder.CAROUSEL_SPEC
        assertNull(RichMediaCache.get(dir, url, spec))

        val bitmap = Bitmap.createBitmap(720, 379, Bitmap.Config.ARGB_8888)
        RichMediaCache.put(dir, url, spec, bitmap)
        assertSame("memory hit", bitmap, RichMediaCache.get(dir, url, spec))
        assertTrue(File(dir, RichMediaCache.key(url, spec) + ".img").isFile)

        RichMediaCache.clearMemory()
        val fromDisk = RichMediaCache.get(dir, url, spec)
        assertNotNull("disk hit", fromDisk)
        assertEquals(720 to 379, fromDisk!!.width to fromDisk.height)

        assertNull("other variant misses", RichMediaCache.get(dir, url, RichNotificationBuilder.CARD_SPEC))
        assertNull("other url misses", RichMediaCache.get(dir, "$url-2", spec))
    }

    @Test
    fun `prune drops files older than two days`() {
        val old = File(dir, "old.img").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3L * 24 * 3600 * 1000) }
        val fresh = File(dir, "fresh.img").apply { writeText("x") }
        RichMediaCache.prune(dir)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    // endregion

    // region review fixes

    @Test
    fun `prune caps the directory by size, oldest first, and spares downloads in progress`() {
        val now = System.currentTimeMillis()
        fun file(name: String, kb: Int, ageMin: Int) = File(dir, name).apply {
            writeBytes(ByteArray(kb * 1024))
            setLastModified(now - ageMin * 60_000L)
        }
        val oldest = file("a.img", 40, 30)
        val middle = file("b.mp4", 40, 20)
        val newest = file("c.img", 40, 10)
        val downloading = file("d.mp4.tmp-7", 40, 40)

        RichMediaCache.prune(dir, now, maxBytes = 100 * 1024)

        assertFalse("oldest dropped first", oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
        assertTrue("a download in progress is not counted nor dropped", downloading.exists())

        RichMediaCache.prune(dir, now, maxBytes = 50 * 1024)
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertEquals(40L * 1024 * 1024, RichMediaCache.MAX_DISK_BYTES)
    }

    @Test
    fun `memory entries backed by a file are released, the others kept`() {
        val spec = RichNotificationBuilder.CAROUSEL_SPEC
        RichMediaCache.put(dir, "https://m.example.com/on-disk", spec, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val unwritable = File(dir, "missing-parent/child")
        RichMediaCache.put(unwritable, "https://m.example.com/memory-only", spec, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        assertEquals(2, RichMediaCache.memoryKeys().size)

        RichMediaCache.releaseMemoryBackedByDisk(dir)

        assertEquals(setOf(RichMediaCache.key("https://m.example.com/memory-only", spec)), RichMediaCache.memoryKeys())
        assertNotNull("still served from the file", RichMediaCache.get(dir, "https://m.example.com/on-disk", spec))
        assertTrue(RichMediaCache.MAX_MEMORY_BYTES <= 4 * 1024 * 1024)
    }

    @Test
    fun `a crop of an opaque source stays opaque, so it can go out as RGB_565`() {
        val photo = Bitmap.createBitmap(1100, 1100, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        val out = RichBitmaps.render(photo, RichNotificationBuilder.IMAGE_SPEC)!!
        assertEquals(1080 to 1080, out.width to out.height)
        assertFalse(out.hasAlpha())
        assertEquals(Bitmap.Config.RGB_565, RichBitmaps.opaqueAsRgb565(out).config)

        val logo = Bitmap.createBitmap(1100, 1100, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        val kept = RichBitmaps.render(logo, RichNotificationBuilder.IMAGE_SPEC)!!
        assertTrue(kept.hasAlpha())
        assertSame(kept, RichBitmaps.opaqueAsRgb565(kept))
    }

    @Test
    fun `blocking timeouts never reach past the deadline`() {
        assertEquals(3_000, timeoutUntil(10_000, 3_000, nowMs = 0))
        assertEquals(500, timeoutUntil(10_000, 3_000, nowMs = 9_500))
        assertEquals(1, timeoutUntil(10_000, 3_000, nowMs = 12_000))
    }

    @Test
    fun `image download stops at its deadline even when bytes keep trickling in`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\n".toByteArray())
                    out.flush()
                    repeat(40) {
                        out.write(ByteArray(8))
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        try {
            val startedAt = System.currentTimeMillis()
            val bitmap = HttpRichImageLoader.load(
                "http://127.0.0.1:${server.localPort}/img",
                RichNotificationBuilder.CAROUSEL_SPEC,
                startedAt + 600
            )
            val elapsed = System.currentTimeMillis() - startedAt
            assertNull(bitmap)
            assertTrue("took $elapsed ms (the body alone takes 4 s)", elapsed < 1_500)
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    // endregion
}
