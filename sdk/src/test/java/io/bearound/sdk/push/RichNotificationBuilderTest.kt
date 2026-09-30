package io.bearound.sdk.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.background.BackgroundScheduler
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on the SDK floor (compat notification path) and on API 34 (POST_NOTIFICATIONS gate). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class RichNotificationBuilderTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val tracker = "https://tracker.example.com"
    private val mediaBase = "https://api.example.com/push-media/"
    private val mediaA = "a".repeat(64)
    private val mediaB = "b".repeat(64)
    private val mediaC = "c".repeat(64)

    private fun marker(sid: String) = """{"t":1,"sid":"$sid","d":"d-$sid","tr":"$tracker"}"""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun data(sid: String, rich: String) = mapOf(
        "bearound" to marker(sid),
        "bearound_rich" to rich,
        "title" to "Weekend deals",
        "body" to "Tap a card to see it"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        BackgroundScheduler._resetForTesting()
        PushEventQueue.resetForTest(context)
        BeAroundSDK.getInstance(context).resetNotificationOpenStateForTest(context)
        SDKConfigStorage.clearConfiguration(context)
        RichNotificationBuilder.resetForTest(context)
        RichNotificationBuilder.imageLoader = RichImageLoader { url, _ ->
            fetched += url
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
        RichNotificationBuilder.hitSender = RichHitSender { url -> hits += url }
        RichNotificationBuilder.videoFrameSource = RichVideoFrameSource { _, _, _, _, _ -> emptyList() }
    }

    @After
    fun tearDown() {
        RichNotificationBuilder.resetForTest(context)
        PushEventQueue.resetForTest(context)
        SDKConfigStorage.clearConfiguration(context)
        BeAroundSDK.getInstance(context).resetNotificationOpenStateForTest(context)
    }

    private fun existingActivityIntent(notificationId: Int, slot: String): Intent? {
        val probe = Intent(context, RichNotificationTrampolineActivity::class.java)
            .setData(Uri.parse("bearound-push://$notificationId/$slot"))
        val pi = PendingIntent.getActivity(
            context, 0, probe, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return null
        return shadowOf(pi).savedIntent
    }

    private fun existingNavIntent(notificationId: Int, slot: String): Intent? {
        val probe = Intent(context, RichNotificationActionReceiver::class.java)
            .setAction(RichNotificationBuilder.ACTION_CAROUSEL_NAV)
            .setData(Uri.parse("bearound-push://$notificationId/$slot"))
        val pi = PendingIntent.getBroadcast(
            context, 0, probe, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return null
        return shadowOf(pi).savedIntent
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    // region carousel

    @Test
    fun `carousel prefetches every card and page turns never hit the network`() {
        val rich = """{"v":1,"f":"CAROUSEL","mb":"$mediaBase","c":[
            {"m":"$mediaA","t":"First","u":"https://shop.example.com/1"},
            {"m":"$mediaB","t":"Second"},
            {"m":"$mediaC","t":"Third"}]}"""
        val payload = data("sid-carousel", rich)
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))
        assertEquals(1, posted().size)
        assertEquals(0, shadowOf(manager).getNotification(id).extras.getInt(RichNotificationBuilder.EXTRA_CARD_INDEX))
        assertNotNull("custom expanded view", shadowOf(manager).getNotification(id).bigContentView)
        // Every card is fetched once, up front. Only the displayed card goes through the
        // tracker view endpoint (that fetch is its view); the others use the raw media URL.
        assertEquals(
            setOf(
                "$tracker/v1/push:view?d=d-sid-carousel&r=${enc(mediaBase + mediaA)}&idx=0",
                mediaBase + mediaB,
                mediaBase + mediaC
            ),
            fetched.toSet()
        )
        assertEquals("first render: $fetched", 3, fetched.size)
        assertTrue("card 0's view was the fetch itself", hits.isEmpty())

        val prev = existingNavIntent(id, "prev")!!
        assertEquals(2, prev.getIntExtra(RichNotificationBuilder.EXTRA_CARD_INDEX, -1))

        val next = existingNavIntent(id, "next")!!
        assertEquals(1, next.getIntExtra(RichNotificationBuilder.EXTRA_CARD_INDEX, -1))
        // A page turn often lands in a fresh process: only the files in cacheDir are left.
        RichMediaCache.clearMemory()
        RichNotificationBuilder.onCarouselNav(context, next)

        assertEquals("same id replaced, not a second notification", 1, posted().size)
        val reposted = shadowOf(manager).getNotification(id)
        assertEquals(1, reposted.extras.getInt(RichNotificationBuilder.EXTRA_CARD_INDEX))
        assertEquals(1_000L, reposted.`when`)
        assertEquals("no network on a page turn: $fetched", 3, fetched.size)
        // Card 1 came from the cache, so its view is reported with a hit, once.
        assertEquals(listOf("$tracker/v1/push:view?d=d-sid-carousel&r=${enc(mediaBase + mediaB)}&idx=1"), hits)

        // After moving to card 1, next points at card 2 and prev back at card 0.
        val nextAgain = existingNavIntent(id, "next")!!
        assertEquals(2, nextAgain.getIntExtra(RichNotificationBuilder.EXTRA_CARD_INDEX, -1))
        val back = existingNavIntent(id, "prev")!!
        assertEquals(0, back.getIntExtra(RichNotificationBuilder.EXTRA_CARD_INDEX, -1))

        // Back to card 0 and forward to card 1 again: both already viewed, no new hit.
        RichNotificationBuilder.onCarouselNav(context, back)
        RichNotificationBuilder.onCarouselNav(context, existingNavIntent(id, "next")!!)
        assertEquals(1, hits.size)
        assertEquals("revisits: $fetched", 3, fetched.size)
    }

    @Test
    fun `carousel index wraps in both directions`() {
        assertEquals(4, RichPushUrls.wrapIndex(-1, 5))
        assertEquals(0, RichPushUrls.wrapIndex(5, 5))
        assertEquals(2, RichPushUrls.wrapIndex(2, 5))
        assertEquals(0, RichPushUrls.wrapIndex(3, 0))
    }

    // endregion

    // region one PendingIntent per card

    @Test
    fun `two images gets one PendingIntent per card with its own target`() {
        val rich = """{"v":1,"f":"TWO_IMAGES","mb":"$mediaBase","c":[
            {"m":"$mediaA","t":"Shoes","u":"https://shop.example.com/shoes?x=1"},
            {"m":"$mediaB","t":"Bags","u":"myapp://deep/bags"}]}"""
        val payload = data("sid-two", rich)
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val card0 = existingActivityIntent(id, "card0")!!
        val card1 = existingActivityIntent(id, "card1")!!
        assertEquals(
            "$tracker/v1/push:click?d=d-sid-two&r=${enc("https://shop.example.com/shoes?x=1")}&idx=0",
            card0.getStringExtra(RichNotificationBuilder.EXTRA_TARGET_URL)
        )
        assertEquals(
            "a deep link opens directly, never through the tracker",
            "myapp://deep/bags",
            card1.getStringExtra(RichNotificationBuilder.EXTRA_TARGET_URL)
        )
        assertEquals(marker("sid-two"), card0.getStringExtra("bearound"))
        assertEquals(
            setOf(
                "$tracker/v1/push:view?d=d-sid-two&r=${enc(mediaBase + mediaA)}&idx=0",
                "$tracker/v1/push:view?d=d-sid-two&r=${enc(mediaBase + mediaB)}&idx=1"
            ),
            fetched.toSet()
        )
    }

    @Test
    fun `card tap reports the open and opens the card target`() {
        val rich = """{"v":1,"f":"IMAGE","mb":"$mediaBase","c":[{"m":"$mediaA","u":"https://shop.example.com/p"}]}"""
        val payload = data("sid-image", rich)
        val id = RichNotificationBuilder.notificationIdFor(payload)
        RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true)
        val tap = existingActivityIntent(id, "card0")!!

        val queued = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(2) // open + received
        PushEventQueue.transport = PushEventTransport { url -> queued += url; latch.countDown(); PushHitOutcome.DRAIN }

        Robolectric.buildActivity(RichNotificationTrampolineActivity::class.java, tap).create()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("hits=$queued", queued.any { it.contains("push:open") && it.contains("d-sid-image") })
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(
            "$tracker/v1/push:click?d=d-sid-image&r=${enc("https://shop.example.com/p")}&idx=0",
            started.dataString
        )
        assertNull("tap dismisses the notification", shadowOf(manager).getNotification(id))
    }

    @Test
    fun `without d and tr in the marker the raw card URLs are used`() {
        val payload = RichPayload.parse(
            """{"v":1,"f":"IMAGE","mb":"$mediaBase","c":[{"m":"$mediaA","u":"https://shop.example.com"}]}"""
        )!!
        assertEquals(mediaBase + mediaA, RichPushUrls.imageFetchUrl(payload, 0, null))
        assertEquals("https://shop.example.com", RichPushUrls.tapUrl("https://shop.example.com", 0, null))
        assertNull("blocked scheme opens the app", RichPushUrls.tapUrl("javascript:alert(1)", 0, null))
        assertNull(RichPushUrls.tapUrl(null, 0, null))
    }

    // endregion

    // region PLAY

    private val videoUrl = "https://media.example.com/clip.mp4"

    private fun playRich(u: String? = videoUrl) =
        if (u == null) """{"v":1,"f":"PLAY","mb":"$mediaBase","c":[{"m":"$mediaA"}]}"""
        else """{"v":1,"f":"PLAY","mb":"$mediaBase","c":[{"m":"$mediaA","u":"$u","vt":"video/mp4"}]}"""

    private fun frames(n: Int) = List(n) { Bitmap.createBitmap(16, 9, Bitmap.Config.RGB_565) }

    @Test
    fun `PLAY parse keeps the video URL only when it is http(s)`() {
        val payload = RichPayload.parse(playRich())!!
        assertEquals(RichFormat.PLAY, payload.format)
        assertEquals(videoUrl, payload.cards[0].url)
        assertEquals(videoUrl, payload.videoUrl())
        assertEquals(mediaBase + mediaA, payload.imageUrl(0))
        assertNull(RichPayload.parse(playRich("myapp://video"))!!.videoUrl())
        assertNull(RichPayload.parse(playRich(null))!!.videoUrl())
        assertNull("other formats carry no video", RichPayload.parse(
            """{"v":1,"f":"IMAGE","mb":"$mediaBase","c":[{"m":"$mediaA","u":"$videoUrl"}]}"""
        )!!.videoUrl())
    }

    @Test
    fun `PLAY with video frames renders the flipper and fetches the poster as the view`() {
        val requested = Collections.synchronizedList(mutableListOf<String>())
        RichNotificationBuilder.videoFrameSource = RichVideoFrameSource { _, url, count, spec, _ ->
            requested += url
            assertEquals(RichNotificationBuilder.FRAME_COUNT, count)
            assertEquals(RichNotificationBuilder.FRAME_SPEC, spec)
            frames(8)
        }
        val payload = data("sid-play", playRich())
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val n = shadowOf(manager).getNotification(id)
        assertEquals(listOf(videoUrl), requested)
        assertNotNull("custom expanded view with the frames", n.bigContentView)
        assertNull("no still picture when the video works", n.extras.getParcelable<Bitmap>(NotificationCompat.EXTRA_PICTURE))
        assertEquals(listOf("$tracker/v1/push:view?d=d-sid-play&r=${enc(mediaBase + mediaA)}&idx=0"), fetched)
        val tap = existingActivityIntent(id, "card0")!!
        assertEquals(videoUrl, tap.getStringExtra(RichNotificationBuilder.EXTRA_VIDEO_URL))
        assertNull("never a browser target", tap.getStringExtra(RichNotificationBuilder.EXTRA_TARGET_URL))
    }

    @Test
    fun `PLAY without usable video falls back to the poster with no play glyph`() {
        val payload = data("sid-play-fallback", playRich())
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val n = shadowOf(manager).getNotification(id)
        assertNotNull("poster as the big picture", n.extras.getParcelable<Bitmap>(NotificationCompat.EXTRA_PICTURE))
        assertEquals(
            "a plain BigPicture, no custom view (so no glyph)",
            Notification.BigPictureStyle::class.java.name,
            n.extras.getString(Notification.EXTRA_TEMPLATE)
        )
        // Tapping still opens the player, which streams the video.
        assertEquals(videoUrl, existingActivityIntent(id, "card0")!!.getStringExtra(RichNotificationBuilder.EXTRA_VIDEO_URL))
    }

    @Test
    fun `PLAY with a single frame is not a video and uses the poster`() {
        RichNotificationBuilder.videoFrameSource = RichVideoFrameSource { _, _, _, _, _ -> frames(1) }
        val payload = data("sid-play-one", playRich())
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        assertNotNull(shadowOf(manager).getNotification(id).extras.getParcelable<Bitmap>(NotificationCompat.EXTRA_PICTURE))
    }

    @Test
    fun `PLAY without video nor poster degrades to title and body`() {
        RichNotificationBuilder.imageLoader = RichImageLoader { _, _ -> null }
        val payload = data("sid-play-none", playRich())
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val n = shadowOf(manager).getNotification(id)
        assertEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Tap a card to see it", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun `PLAY tap reports the open, fires the click hit and opens the SDK player`() {
        RichNotificationBuilder.videoFrameSource = RichVideoFrameSource { _, _, _, _, _ -> frames(8) }
        val payload = data("sid-play-tap", playRich())
        val id = RichNotificationBuilder.notificationIdFor(payload)
        RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true)
        val tap = existingActivityIntent(id, "card0")!!

        val queued = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(2) // open + received
        PushEventQueue.transport = PushEventTransport { url -> queued += url; latch.countDown(); PushHitOutcome.DRAIN }

        Robolectric.buildActivity(RichNotificationTrampolineActivity::class.java, tap).create()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("hits=$queued", queued.any { it.contains("push:open") && it.contains("d-sid-play-tap") })
        assertEquals(listOf("$tracker/v1/push:click?d=d-sid-play-tap&r=${enc(videoUrl)}&idx=0"), hits)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(RichPushVideoActivity::class.java.name, started.component?.className)
        assertEquals(videoUrl, started.getStringExtra(RichNotificationBuilder.EXTRA_VIDEO_URL))
        assertNull("tap dismisses the notification", shadowOf(manager).getNotification(id))
    }

    @Test
    fun `PLAY click hit needs d and tr in the marker`() {
        assertNull(RichPushUrls.clickHitUrl(videoUrl, 0, null))
        assertNull(RichPushUrls.clickHitUrl("myapp://video", 0, PushMarker("s", "d", tracker)))
        assertEquals(
            "$tracker/v1/push:click?d=d&r=${enc(videoUrl)}&idx=0",
            RichPushUrls.clickHitUrl(videoUrl, 0, PushMarker("s", "d", tracker))
        )
    }

    // endregion

    // region legacy path

    @Test
    fun `push without bearound_rich posts nothing and still reports received`() {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        PushEventQueue.transport = PushEventTransport { url -> hits += url; latch.countDown(); PushHitOutcome.DRAIN }

        val handled = BeAroundSDK.getInstance(context).handleRemoteMessage(
            mapOf("bearound" to marker("sid-legacy"), "title" to "t", "body" to "b")
        )

        assertTrue(handled)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(hits.any { it.contains("push:received") && it.contains("d-sid-legacy") })
        Thread.sleep(100)
        assertTrue("SDK must not render a legacy push", posted().isEmpty())
    }

    @Test
    fun `unknown contract version renders title and body only`() {
        val payload = data("sid-v2", """{"v":2,"f":"IMAGE","mb":"$mediaBase","c":[{"m":"$mediaA"}]}""")
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val n = shadowOf(manager).getNotification(id)
        assertEquals("Weekend deals", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Tap a card to see it", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertFalse(n.extras.containsKey(RichNotificationBuilder.EXTRA_CARD_INDEX))
        assertTrue("no image fetched", fetched.isEmpty())
    }

    @Test
    fun `failed image download degrades to title and body`() {
        RichNotificationBuilder.imageLoader = RichImageLoader { _, _ -> null }
        val payload = data("sid-fail", """{"v":1,"f":"IMAGE","mb":"$mediaBase","c":[{"m":"$mediaA"}]}""")
        val id = RichNotificationBuilder.notificationIdFor(payload)

        assertTrue(RichNotificationBuilder.post(context, payload, id, 0, 1_000L, true))

        val n = shadowOf(manager).getNotification(id)
        assertNull(n.extras.getParcelable<Bitmap>(NotificationCompat.EXTRA_PICTURE))
        assertEquals("Tap a card to see it", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    // endregion
}
