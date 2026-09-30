package io.bearound.sdk.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.R
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Rich push formats carried by `bearound_rich.f` (contract v1). */
internal enum class RichFormat { IMAGE, TWO_IMAGES, CAROUSEL, PLAY }

/** One card of a rich push: raw media id, optional caption and optional tap target. */
internal data class RichCard(val mediaId: String, val caption: String?, val url: String?)

/**
 * The `bearound_rich` payload (contract v1): `{"v":1,"f":..,"mb":..,"c":[{"m","t","u"}]}`.
 * The image of card `i` lives at `mb + c[i].m`.
 */
internal data class RichPayload(
    val format: RichFormat,
    val mediaBase: String,
    val cards: List<RichCard>
) {
    fun imageUrl(index: Int): String = mediaBase + cards[index].mediaId

    /** PLAY only: the direct MP4 URL (card `u`), when it is http(s). */
    fun videoUrl(): String? =
        if (format == RichFormat.PLAY) cards[0].url?.takeIf { RichPushUrls.isWebUrl(it) } else null

    companion object {
        const val SUPPORTED_VERSION = 1
        const val MAX_CAROUSEL_CARDS = 5

        private val MEDIA_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

        /**
         * Parses the `bearound_rich` JSON string. Null when absent, malformed, of an unknown
         * version or format, or without enough valid cards for its format: the caller then
         * renders the legacy notification (title + body only).
         */
        fun parse(raw: String?): RichPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                if (json.optInt("v", -1) != SUPPORTED_VERSION) return null
                val format = try {
                    RichFormat.valueOf(json.optString("f"))
                } catch (_: IllegalArgumentException) {
                    return null
                }
                val mediaBase = json.optString("mb")
                if (!mediaBase.startsWith("https://", ignoreCase = true) &&
                    !mediaBase.startsWith("http://", ignoreCase = true)
                ) return null
                val base = if (mediaBase.endsWith("/")) mediaBase else "$mediaBase/"

                val array = json.optJSONArray("c") ?: return null
                val cards = mutableListOf<RichCard>()
                for (i in 0 until array.length()) {
                    val c = array.optJSONObject(i) ?: return null
                    val mediaId = c.optString("m")
                    if (!MEDIA_ID.matches(mediaId)) return null
                    cards += RichCard(
                        mediaId = mediaId,
                        caption = c.optString("t").takeIf { it.isNotBlank() },
                        url = c.optString("u").takeIf { it.isNotBlank() }
                    )
                }
                val usable = when (format) {
                    RichFormat.IMAGE, RichFormat.PLAY -> cards.take(1)
                    RichFormat.TWO_IMAGES -> if (cards.size >= 2) cards.take(2) else return null
                    RichFormat.CAROUSEL -> cards.take(MAX_CAROUSEL_CARDS)
                }
                if (usable.isEmpty()) return null
                RichPayload(format, base, usable)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * Per-card URLs, built on the device from the `bearound` marker (contract v1): the payload
 * only carries raw media ids and raw card URLs to stay under the push size limit.
 */
internal object RichPushUrls {

    /**
     * URL to fetch card [index]'s image. With a measurable [marker] it goes through the
     * tracker view endpoint (that fetch IS the view); otherwise it is the raw media URL.
     */
    fun imageFetchUrl(payload: RichPayload, index: Int, marker: PushMarker?): String {
        val raw = payload.imageUrl(index)
        if (marker == null) return raw
        return "${trackerBase(marker)}/v1/push:view?d=${enc(marker.d)}&r=${enc(raw)}&idx=$index"
    }

    /**
     * Where a tap on card [index] goes. Null: open the app (same as a legacy push tap).
     * Allowlist: http(s) goes through the tracker click endpoint when [marker] is measurable,
     * raw otherwise. Any other URI is a deep link, kept only when [hostHandles] says an
     * Activity of the host app itself opens it (opened directly, never through the tracker);
     * everything else (`javascript:`, `file:`, `intent:`, another app's scheme...) opens the app.
     */
    fun tapUrl(rawUrl: String?, index: Int, marker: PushMarker?, hostHandles: (String) -> Boolean): String? {
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isWebUrl(url)) return url.takeIf { hostHandles(it) }
        if (marker == null) return url
        return "${trackerBase(marker)}/v1/push:click?d=${enc(marker.d)}&r=${enc(url)}&idx=$index"
    }

    /**
     * Whether an ACTION_VIEW of [url] resolves to an Activity of the host app. Querying the
     * app's own package needs no `<queries>` entry (package visibility never hides it).
     */
    fun resolvesInHost(context: Context, url: String): Boolean = try {
        val uri = url.toUri()
        if (uri.scheme.isNullOrBlank()) false
        else context.packageManager.queryIntentActivities(hostViewIntent(context, uri), 0).isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /** ACTION_VIEW of [uri] restricted to the host app, so no other app can claim a deep link. */
    fun hostViewIntent(context: Context, uri: android.net.Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)

    /**
     * The tracker click hit for a tap the SDK handles itself (the PLAY player): the tracker
     * URL when [marker] is measurable and [rawUrl] is http(s), null otherwise (nothing to report).
     */
    fun clickHitUrl(rawUrl: String?, index: Int, marker: PushMarker?): String? {
        val url = rawUrl?.trim()?.takeIf { isWebUrl(it) } ?: return null
        if (marker == null) return null
        return "${trackerBase(marker)}/v1/push:click?d=${enc(marker.d)}&r=${enc(url)}&idx=$index"
    }

    fun isWebUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** Carousel index wrap-around: -1 is the last card, `count` is the first. */
    fun wrapIndex(index: Int, count: Int): Int {
        if (count <= 0) return 0
        return ((index % count) + count) % count
    }

    private fun trackerBase(marker: PushMarker) = marker.tr.trimEnd('/')

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Builds and posts the notification of a rich push (data-only FCM carrying
 * `bearound_rich`). Reached from [BeAroundSDK.handleRemoteMessage], so it works both with
 * the SDK's optional [BearoundMessagingService] and with a host service that forwards
 * messages to the public API.
 *
 * - IMAGE: `BigPictureStyle`.
 * - TWO_IMAGES: two cards side by side, one `PendingIntent` per card.
 * - CAROUSEL: one card at a time. The first render prefetches EVERY card into
 *   [RichMediaCache] (`cacheDir`), so prev/next re-post the SAME notification id (and tag)
 *   through [RichNotificationActionReceiver] without touching the network. On a metered
 *   network or with Data Saver only the shown card is fetched; the others load on their turn.
 * - PLAY: the video's own frames in a self-advancing `ViewFlipper` (an animated preview);
 *   tapping opens [RichPushVideoActivity], which plays the video with sound. If the video
 *   cannot be used, or is not downloaded (metered network or Data Saver), the poster is shown
 *   (no play glyph), else title + body. The tap still opens the player.
 *
 * Rendering runs on a worker thread ([show]) so the FCM callback returns at once. Nothing is
 * downloaded when the notification could not be shown (permission, app or channel off).
 *
 * Every tap goes through [RichNotificationTrampolineActivity] (an Activity, so it is
 * allowed on Android 12+), which reports the `open` with the SDK's existing open
 * measurement and then opens the card target. If an image cannot be downloaded the
 * notification degrades to title + body. Every bitmap is cropped and downscaled to its box
 * ([RichImageSpec]) so a RemoteViews stays under the platform's size warning.
 */
internal object RichNotificationBuilder {

    private const val TAG = "BeAroundSDK-RichPush"

    const val KEY_RICH = "bearound_rich"
    const val KEY_MARKER = "bearound"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"

    const val CHANNEL_ID = "bearound_rich_push"
    private const val HOST_DEFAULT_CHANNEL_META = "com.google.firebase.messaging.default_notification_channel_id"
    private const val HOST_DEFAULT_ICON_META = "com.google.firebase.messaging.default_notification_icon"

    const val ACTION_CAROUSEL_NAV = "io.bearound.sdk.push.ACTION_CAROUSEL_NAV"
    const val EXTRA_DATA = "io.bearound.sdk.push.extra.DATA"
    const val EXTRA_NOTIFICATION_ID = "io.bearound.sdk.push.extra.NOTIFICATION_ID"
    const val EXTRA_CARD_INDEX = "io.bearound.sdk.push.extra.CARD_INDEX"
    const val EXTRA_WHEN = "io.bearound.sdk.push.extra.WHEN"
    const val EXTRA_TARGET_URL = "io.bearound.sdk.push.extra.TARGET_URL"
    const val EXTRA_VIDEO_URL = "io.bearound.sdk.push.extra.VIDEO_URL"
    const val EXTRA_VIEWED = "io.bearound.sdk.push.extra.VIEWED"

    private const val URI_SCHEME = "bearound-push"
    private const val SLOT_CONTENT = "content"
    private const val SLOT_PREV = "prev"
    private const val SLOT_NEXT = "next"

    /** Notification tag: keeps the SDK's ids apart from the host app's own notifications. */
    const val NOTIFICATION_TAG = "bearound_rich"

    /** Whole budget of one image load (every card of a render in parallel). */
    internal const val DOWNLOAD_BUDGET_MS = 8_000L

    /** PLAY: the video download must end by then; frame extraction by [RENDER_BUDGET_MS]. */
    internal const val VIDEO_DOWNLOAD_BUDGET_MS = 6_500L
    internal const val RENDER_BUDGET_MS = 9_000L

    /** IMAGE: the BigPicture keeps the source aspect, at most 1080 px on either edge. */
    internal val IMAGE_SPEC = RichImageSpec(1080, 1080, null)

    /** Collapsed thumbnail (IMAGE large icon, PLAY first frame). */
    internal val THUMB_SPEC = RichImageSpec(256, 256, 1f)
    internal val VIDEO_THUMB_SPEC = RichImageSpec(256, 144, 16f / 9f, Bitmap.Config.RGB_565)

    /** CAROUSEL: full-width 150dp box, about 1.9:1 on a phone. */
    internal val CAROUSEL_SPEC = RichImageSpec(720, 380, 1.9f)

    /** TWO_IMAGES: half-width 112dp boxes, about 1.25:1. Two of them share one RemoteViews. */
    internal val CARD_SPEC = RichImageSpec(480, 384, 1.25f)

    /** PLAY: 8 frames of a 16:9 box; all of them live in the same RemoteViews. */
    internal const val FRAME_COUNT = 8
    internal const val FRAME_BUDGET_BYTES = 1_800_000L
    internal val FRAME_SPEC = RichBitmaps.frameSpec(
        FRAME_BUDGET_BYTES, FRAME_COUNT, 16f / 9f, 720, Bitmap.Config.RGB_565
    )

    private val FRAME_VIEW_IDS = intArrayOf(
        R.id.bearound_push_play_frame_0, R.id.bearound_push_play_frame_1,
        R.id.bearound_push_play_frame_2, R.id.bearound_push_play_frame_3,
        R.id.bearound_push_play_frame_4, R.id.bearound_push_play_frame_5,
        R.id.bearound_push_play_frame_6, R.id.bearound_push_play_frame_7
    )

    @Volatile
    internal var imageLoader: RichImageLoader = HttpRichImageLoader

    @Volatile
    internal var videoFrameSource: RichVideoFrameSource = HttpRichVideoFrameSource

    @Volatile
    internal var hitSender: RichHitSender = HttpRichHitSender

    /** Metered network or Data Saver: no automatic video download, no carousel prefetch. */
    @Volatile
    internal var networkConstrained: (Context) -> Boolean = { RichNetworkPolicy.isConstrained(it) }

    /** Image load budget; a test seam, [DOWNLOAD_BUDGET_MS] in production. */
    @Volatile
    internal var downloadBudgetMs: Long = DOWNLOAD_BUDGET_MS

    internal fun resetForTest(context: Context? = null) {
        imageLoader = HttpRichImageLoader
        videoFrameSource = HttpRichVideoFrameSource
        hitSender = HttpRichHitSender
        networkConstrained = { RichNetworkPolicy.isConstrained(it) }
        downloadBudgetMs = DOWNLOAD_BUDGET_MS
        RichMediaCache.clearMemory()
        context?.let { RichMediaCache.clearDisk(RichMediaCache.dir(it)) }
    }

    /**
     * Entry point from `handleRemoteMessage`. Never throws and never blocks: downloads run on
     * a worker thread, so the caller (FCM's message thread, or a bridge's UI thread) returns
     * at once and the notification is posted when its media is ready (within
     * [RENDER_BUDGET_MS] plus decode time). Returns the worker, for tests.
     */
    fun show(context: Context, data: Map<String, String>): Thread? {
        return try {
            val appContext = context.applicationContext
            val snapshot = HashMap(data)
            thread(name = "bearound-rich-push") {
                try {
                    post(appContext, snapshot, notificationIdFor(snapshot), 0, System.currentTimeMillis(), true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Rich notification failed: ${t.message}")
                } finally {
                    try {
                        RichMediaCache.prune(RichMediaCache.dir(appContext))
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Rich notification failed to start: ${t.message}")
            null
        }
    }

    /** Carousel prev/next: re-posts the same notification id at the requested index. */
    fun onCarouselNav(context: Context, intent: Intent) {
        val data = bundleToMap(intent.getBundleExtra(EXTRA_DATA)) ?: return
        if (!intent.hasExtra(EXTRA_NOTIFICATION_ID)) return
        val startedAt = System.currentTimeMillis()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val index = intent.getIntExtra(EXTRA_CARD_INDEX, 0)
        val whenMs = intent.getLongExtra(EXTRA_WHEN, System.currentTimeMillis())
        val viewed = intent.getIntExtra(EXTRA_VIEWED, 0)
        post(context, data, notificationId, index, whenMs, false, viewed)
        Log.d(TAG, "Carousel page ${index + 1} re-posted in ${System.currentTimeMillis() - startedAt} ms")
    }

    /**
     * Card or notification tap, run by [RichNotificationTrampolineActivity]: reports the
     * `open` (and the implied `received`) through the SDK's existing open measurement,
     * dismisses the notification, then opens the card target, the video player or the app.
     */
    fun onTap(activity: Activity, intent: Intent) {
        // From the data bundle: the SDK's lifecycle callbacks report the open (and strip the
        // top-level `bearound` extra) in onActivityCreated, before this runs.
        val marker = PushMarker.parse(
            intent.getBundleExtra(EXTRA_DATA)?.getString(KEY_MARKER) ?: intent.getStringExtra(KEY_MARKER)
        )
        try {
            BeAroundSDK.getInstance(activity.applicationContext).handleNotificationIntent(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Open report failed: ${t.message}")
        }
        if (intent.hasExtra(EXTRA_NOTIFICATION_ID)) {
            NotificationManagerCompat.from(activity).cancel(NOTIFICATION_TAG, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        val video = intent.getStringExtra(EXTRA_VIDEO_URL)
        if (video != null) {
            // The player start is the click: reported to the tracker without following its redirect.
            RichPushUrls.clickHitUrl(video, 0, marker)?.let {
                try {
                    hitSender.send(it)
                } catch (_: Throwable) {
                }
            }
            // CLEAR_TASK: a player left in the background by an earlier push is replaced,
            // never resumed showing the old video.
            activity.startActivity(
                Intent(activity, RichPushVideoActivity::class.java)
                    .putExtra(EXTRA_VIDEO_URL, video)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            return
        }
        val target = intent.getStringExtra(EXTRA_TARGET_URL)
        if (target != null) {
            try {
                // A deep link only ever opens inside the host app (see RichPushUrls.tapUrl).
                val view = if (RichPushUrls.isWebUrl(target)) Intent(Intent.ACTION_VIEW, target.toUri())
                else RichPushUrls.hostViewIntent(activity, target.toUri())
                activity.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "No app handles the card target; opening the host app")
            }
        }
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Same extras a system-rendered push tap hands the launcher Activity, minus the
        // marker (already reported above) and the rich payload.
        bundleToMap(intent.getBundleExtra(EXTRA_DATA))?.forEach { (k, v) ->
            if (k != KEY_MARKER && k != KEY_RICH) launch.putExtra(k, v)
        }
        activity.startActivity(launch)
    }

    /** Stable per push: carousel re-posts reuse it; a second push with the same `sid` replaces the first. */
    internal fun notificationIdFor(data: Map<String, String>): Int {
        val sid = try {
            data[KEY_MARKER]?.let { JSONObject(it).optString("sid").takeIf { s -> s.isNotBlank() } }
        } catch (_: Throwable) {
            null
        }
        return ("bearound_rich:" + (sid ?: data[KEY_RICH].orEmpty())).hashCode()
    }

    @SuppressLint("MissingPermission")
    internal fun post(
        context: Context,
        data: Map<String, String>,
        notificationId: Int,
        cardIndex: Int,
        whenMs: Long,
        firstRender: Boolean,
        viewedMask: Int = 0
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted; rich notification skipped")
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channelId = resolveChannel(context)
        if (!channelCanShow(context, channelId)) {
            Log.i(TAG, "Notification channel blocked; rich notification skipped")
            return false
        }
        val notification = build(context, data, notificationId, cardIndex, whenMs, firstRender, viewedMask, channelId)
            ?: return false
        manager.notify(NOTIFICATION_TAG, notificationId, notification)
        // System UI holds its own copy now; page turns read the files.
        RichMediaCache.releaseMemoryBackedByDisk(RichMediaCache.dir(context))
        return true
    }

    /** False when the user turned the channel off (API 26+): nothing would show. */
    private fun channelCanShow(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    internal fun build(
        context: Context,
        data: Map<String, String>,
        notificationId: Int,
        cardIndex: Int,
        whenMs: Long,
        firstRender: Boolean,
        viewedMask: Int = 0,
        channelId: String = resolveChannel(context)
    ): Notification? {
        val title = data[KEY_TITLE].orEmpty()
        val body = data[KEY_BODY].orEmpty()
        val payload = RichPayload.parse(data[KEY_RICH])
        val marker = PushMarker.parse(data[KEY_MARKER])
        val dataBundle = mapToBundle(data)
        val cacheDir = RichMediaCache.dir(context)
        val startedAt = System.currentTimeMillis()
        val imageDeadline = startedAt + downloadBudgetMs

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val videoUrl = payload?.videoUrl()

        fun cardIntent(index: Int): PendingIntent {
            if (videoUrl != null) {
                return tapIntent(context, dataBundle, notificationId, "card$index", null, videoUrl)
            }
            val target = payload?.let {
                RichPushUrls.tapUrl(it.cards[index].url, index, marker) { url -> RichPushUrls.resolvesInHost(context, url) }
            }
            return tapIntent(context, dataBundle, notificationId, "card$index", target, null)
        }

        val openAppIntent = tapIntent(context, dataBundle, notificationId, SLOT_CONTENT, null, null)

        fun plain(): Notification? {
            if (title.isBlank() && body.isBlank()) return null
            // A single-card format keeps its target when it degrades (a PLAY push still
            // opens the player); multi-card formats open the app.
            val content = if (payload != null && payload.cards.size == 1) cardIntent(0) else openAppIntent
            return builder
                .setContentIntent(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .build()
        }

        fun bigPicture(source: Bitmap, content: PendingIntent): Notification {
            // An opaque picture (any photo) goes out as RGB_565: half the bytes.
            val picture = RichBitmaps.opaqueAsRgb565(source)
            val thumb = RichBitmaps.render(source, THUMB_SPEC)
            logBytes(payload?.format, "bigPicture", RichBitmaps.remoteViewsBytes(listOf(picture)), RichBitmaps.remoteViewsBytes(listOf(thumb)))
            return builder
                .setLargeIcon(thumb)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(picture)
                        .bigLargeIcon(null as Bitmap?)
                )
                .setContentIntent(content)
                .build()
        }

        if (payload == null) return plain()

        val index = RichPushUrls.wrapIndex(cardIndex, payload.cards.size)
        builder.extras.putInt(EXTRA_CARD_INDEX, index)

        /** Counts the view of each displayed card once per push (see [loadCards]). */
        fun reportViews(loaded: List<LoadedCard>, displayed: Collection<Int>, alreadyViewed: Int = 0) {
            marker ?: return
            displayed.forEach { i ->
                if (alreadyViewed and (1 shl i) != 0) return@forEach
                if (loaded.firstOrNull { it.index == i }?.fetchedAsView == true) return@forEach
                try {
                    hitSender.send(RichPushUrls.imageFetchUrl(payload, i, marker))
                } catch (_: Throwable) {
                }
            }
        }

        return when (payload.format) {
            RichFormat.IMAGE -> {
                val loaded = loadCards(cacheDir, payload, listOf(0), marker, IMAGE_SPEC, setOf(0), imageDeadline)
                val bitmap = loaded[0].bitmap ?: return plain()
                reportViews(loaded, listOf(0))
                bigPicture(bitmap, cardIntent(0))
            }

            RichFormat.TWO_IMAGES -> {
                val loaded = loadCards(cacheDir, payload, listOf(0, 1), marker, CARD_SPEC, setOf(0, 1), imageDeadline)
                if (loaded.any { it.bitmap == null }) return plain()
                reportViews(loaded, listOf(0, 1))
                val expanded = RemoteViews(context.packageName, R.layout.bearound_notification_two_images)
                bindHeader(expanded, title, body)
                val slots = listOf(
                    Triple(R.id.bearound_push_card_0, R.id.bearound_push_card_0_image, R.id.bearound_push_card_0_caption),
                    Triple(R.id.bearound_push_card_1, R.id.bearound_push_card_1_image, R.id.bearound_push_card_1_caption)
                )
                slots.forEachIndexed { i, (root, image, caption) ->
                    expanded.setImageViewBitmap(image, loaded[i].bitmap)
                    bindOptionalText(expanded, caption, payload.cards[i].caption)
                    expanded.setOnClickPendingIntent(root, cardIntent(i))
                }
                logBytes(payload.format, "expanded", RichBitmaps.remoteViewsBytes(loaded.map { it.bitmap }), 0)
                decorated(builder, context, title, body, expanded, null)
                    .setContentIntent(openAppIntent)
                    .build()
            }

            RichFormat.CAROUSEL -> {
                val count = payload.cards.size
                // First render: prefetch every card, so page turns never wait on the network.
                // Later renders read the cache (the network is only a fallback for a miss).
                // On a metered network or with Data Saver only the shown card is fetched; the
                // others load on their page turn.
                val prefetch = firstRender && !networkConstrained(context)
                val indices = if (prefetch) (0 until count).toList() else listOf(index)
                val loaded = loadCards(cacheDir, payload, indices, marker, CAROUSEL_SPEC, setOf(index), imageDeadline)
                val bitmap = loaded.first { it.index == index }.bitmap
                if (bitmap == null && firstRender) return plain()
                reportViews(loaded, listOf(index), viewedMask)
                val viewed = viewedMask or (1 shl index)
                val expanded = RemoteViews(context.packageName, R.layout.bearound_notification_carousel)
                bindHeader(expanded, title, body)
                if (bitmap != null) {
                    expanded.setImageViewBitmap(R.id.bearound_push_carousel_image, bitmap)
                    expanded.setViewVisibility(R.id.bearound_push_carousel_image, View.VISIBLE)
                } else {
                    expanded.setViewVisibility(R.id.bearound_push_carousel_image, View.INVISIBLE)
                }
                bindOptionalText(expanded, R.id.bearound_push_carousel_caption, payload.cards[index].caption)
                expanded.setTextViewText(R.id.bearound_push_carousel_counter, "${index + 1}/$count")
                expanded.setOnClickPendingIntent(R.id.bearound_push_carousel_image, cardIntent(index))
                expanded.setOnClickPendingIntent(R.id.bearound_push_carousel_caption, cardIntent(index))
                if (count > 1) {
                    expanded.setOnClickPendingIntent(
                        R.id.bearound_push_carousel_prev,
                        navIntent(context, dataBundle, notificationId, SLOT_PREV, RichPushUrls.wrapIndex(index - 1, count), whenMs, viewed)
                    )
                    expanded.setOnClickPendingIntent(
                        R.id.bearound_push_carousel_next,
                        navIntent(context, dataBundle, notificationId, SLOT_NEXT, RichPushUrls.wrapIndex(index + 1, count), whenMs, viewed)
                    )
                } else {
                    expanded.setViewVisibility(R.id.bearound_push_carousel_prev, View.GONE)
                    expanded.setViewVisibility(R.id.bearound_push_carousel_next, View.GONE)
                    expanded.setViewVisibility(R.id.bearound_push_carousel_counter, View.GONE)
                }
                if (firstRender) logBytes(payload.format, "expanded", RichBitmaps.remoteViewsBytes(listOf(bitmap)), 0)
                decorated(builder, context, title, body, expanded, null)
                    .setContentIntent(cardIntent(index))
                    .build()
            }

            RichFormat.PLAY -> {
                // The poster fetch (push:view, idx 0) is the view and the fallback; it runs
                // alongside the video download.
                val posterLoaded = AtomicReference<List<LoadedCard>?>(null)
                val posterWorker = thread(name = "bearound-rich-poster") {
                    posterLoaded.set(loadCards(cacheDir, payload, listOf(0), marker, IMAGE_SPEC, setOf(0), imageDeadline))
                }
                // Metered network or Data Saver: no automatic video download. The poster shows
                // (no play glyph) and a tap still opens the player, which streams on demand.
                val skipVideo = videoUrl != null && networkConstrained(context)
                if (skipVideo) Log.i(TAG, "PLAY video not downloaded on a metered network or with Data Saver")
                val frames = if (videoUrl == null || skipVideo) emptyList() else try {
                    videoFrameSource.frames(
                        context, videoUrl, FRAME_COUNT, FRAME_SPEC,
                        startedAt + VIDEO_DOWNLOAD_BUDGET_MS, startedAt + RENDER_BUDGET_MS
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                posterWorker.join((imageDeadline - System.currentTimeMillis()).coerceAtLeast(1))
                val poster = posterLoaded.get()
                poster?.let { reportViews(it, listOf(0)) }

                if (frames.size >= 2) {
                    playNotification(builder, context, title, body, frames, cardIntent(0))
                } else {
                    if (videoUrl != null && !skipVideo) Log.w(TAG, "PLAY video unavailable; showing the poster")
                    val cover = poster?.firstOrNull()?.bitmap ?: return plain()
                    bigPicture(cover, cardIntent(0))
                }
            }
        }
    }

    /** PLAY with video: every flipper slot gets a frame; fewer frames repeat to keep an even pace. */
    private fun playNotification(
        builder: NotificationCompat.Builder,
        context: Context,
        title: String,
        body: String,
        frames: List<Bitmap>,
        content: PendingIntent
    ): Notification {
        val expanded = RemoteViews(context.packageName, R.layout.bearound_notification_play)
        bindHeader(expanded, title, body)
        FRAME_VIEW_IDS.forEachIndexed { slot, viewId ->
            // A ViewFlipper shows every child in turn (it overrides their visibility), so no
            // slot is left empty: a repeated frame is the same Bitmap and costs no extra bytes.
            expanded.setImageViewBitmap(viewId, frames[RichFrameTimes.frameForSlot(slot, FRAME_VIEW_IDS.size, frames.size)])
        }
        expanded.setOnClickPendingIntent(R.id.bearound_push_play_frame, content)
        val thumb = RichBitmaps.render(frames[0], VIDEO_THUMB_SPEC)
        logBytes(RichFormat.PLAY, "expanded", RichBitmaps.remoteViewsBytes(frames), RichBitmaps.remoteViewsBytes(listOf(thumb)))
        return decorated(builder, context, title, body, expanded, thumb)
            .setContentIntent(content)
            .build()
    }

    private fun logBytes(format: RichFormat?, label: String, main: Long, collapsed: Long) {
        Log.d(TAG, "$format RemoteViews bitmaps: $label=$main B, collapsed=$collapsed B")
    }

    private fun decorated(
        builder: NotificationCompat.Builder,
        context: Context,
        title: String,
        body: String,
        expanded: RemoteViews,
        thumbnail: Bitmap?
    ): NotificationCompat.Builder {
        val collapsed = RemoteViews(context.packageName, R.layout.bearound_notification_collapsed)
        bindHeader(collapsed, title, body)
        if (thumbnail != null) {
            collapsed.setImageViewBitmap(R.id.bearound_push_thumb, thumbnail)
            collapsed.setViewVisibility(R.id.bearound_push_thumb, View.VISIBLE)
        }
        return builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
    }

    private fun bindHeader(views: RemoteViews, title: String, body: String) {
        bindOptionalText(views, R.id.bearound_push_title, title)
        bindOptionalText(views, R.id.bearound_push_body, body)
    }

    private fun bindOptionalText(views: RemoteViews, viewId: Int, text: String?) {
        if (text.isNullOrBlank()) {
            views.setViewVisibility(viewId, View.GONE)
        } else {
            views.setTextViewText(viewId, text)
            views.setViewVisibility(viewId, View.VISIBLE)
        }
    }

    /** Every tap opens UI, so it is an Activity PendingIntent (Android 12+ trampoline rules). */
    private fun tapIntent(
        context: Context,
        data: Bundle,
        notificationId: Int,
        slot: String,
        targetUrl: String?,
        videoUrl: String?
    ): PendingIntent {
        val intent = Intent(context, RichNotificationTrampolineActivity::class.java).apply {
            // Distinct data per (notification, slot): PendingIntents differing only in extras
            // would otherwise collapse into one and every card would open the same target.
            setData("$URI_SCHEME://$notificationId/$slot".toUri())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            data.getString(KEY_MARKER)?.let { putExtra(KEY_MARKER, it) }
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_DATA, data)
            if (targetUrl != null) putExtra(EXTRA_TARGET_URL, targetUrl)
            if (videoUrl != null) putExtra(EXTRA_VIDEO_URL, videoUrl)
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun navIntent(
        context: Context,
        data: Bundle,
        notificationId: Int,
        slot: String,
        targetIndex: Int,
        whenMs: Long,
        viewedMask: Int
    ): PendingIntent {
        val intent = Intent(context, RichNotificationActionReceiver::class.java).apply {
            action = ACTION_CAROUSEL_NAV
            setData("$URI_SCHEME://$notificationId/$slot".toUri())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_CARD_INDEX, targetIndex)
            putExtra(EXTRA_WHEN, whenMs)
            putExtra(EXTRA_VIEWED, viewedMask)
            putExtra(EXTRA_DATA, data)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** One card's prepared bitmap, and whether its network fetch went through the tracker view endpoint. */
    internal class LoadedCard(val index: Int, val bitmap: Bitmap?, val fetchedAsView: Boolean)

    /**
     * Loads [indices] in parallel, all of them bound by one [deadlineMs]: cache first (memory,
     * then `cacheDir`), network on a miss. A card not loaded by the deadline counts as failed,
     * even when its loader ignores the deadline. A displayed card ([displayed]) is fetched
     * through the tracker view endpoint (that fetch IS the view); a prefetched card is fetched
     * from the raw media URL, so prefetching never counts a view. A displayed card served from
     * the cache gets its view reported by the caller instead.
     */
    private fun loadCards(
        cacheDir: File,
        payload: RichPayload,
        indices: List<Int>,
        marker: PushMarker?,
        spec: RichImageSpec,
        displayed: Set<Int>,
        deadlineMs: Long
    ): List<LoadedCard> {
        fun loadOne(index: Int): LoadedCard {
            val key = payload.imageUrl(index)
            RichMediaCache.get(cacheDir, key, spec)?.let { return LoadedCard(index, it, false) }
            val asView = index in displayed && marker != null
            val url = if (index in displayed) RichPushUrls.imageFetchUrl(payload, index, marker) else key
            val bitmap = try {
                imageLoader.load(url, spec, deadlineMs)
            } catch (_: Throwable) {
                null
            } ?: return LoadedCard(index, null, false)
            RichMediaCache.put(cacheDir, key, spec, bitmap)
            return LoadedCard(index, bitmap, asView)
        }
        val results = java.util.concurrent.atomic.AtomicReferenceArray<LoadedCard?>(indices.size)
        val workers = indices.mapIndexed { pos, index ->
            thread(name = "bearound-rich-image-$pos") { results.set(pos, loadOne(index)) }
        }
        workers.forEach { it.join((deadlineMs - System.currentTimeMillis()).coerceAtLeast(1)) }
        return indices.mapIndexed { pos, index -> results.get(pos) ?: LoadedCard(index, null, false) }
    }

    /** The host's channel from the standard FCM meta-data when it exists, else the SDK's own. */
    private fun resolveChannel(context: Context): String {
        if (Build.VERSION.SDK_INT < 26) return CHANNEL_ID
        val manager = context.getSystemService(NotificationManager::class.java) ?: return CHANNEL_ID
        val hostChannel = metaData(context)?.getString(HOST_DEFAULT_CHANNEL_META)
        if (!hostChannel.isNullOrBlank() && manager.getNotificationChannel(hostChannel) != null) return hostChannel
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.bearound_rich_push_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        return CHANNEL_ID
    }

    /** The host's FCM default notification icon when declared, else the app icon. */
    private fun resolveSmallIcon(context: Context): Int {
        val declared = metaData(context)?.getInt(HOST_DEFAULT_ICON_META, 0) ?: 0
        val icon = if (declared != 0) declared else context.applicationInfo.icon
        if (icon == 0) return android.R.drawable.sym_def_app_icon
        // Android 8.0 crashes System UI on an adaptive icon used as a small icon.
        if (Build.VERSION.SDK_INT == 26) {
            val drawable = try { ContextCompat.getDrawable(context, icon) } catch (_: Throwable) { null }
            if (drawable == null || drawable is android.graphics.drawable.AdaptiveIconDrawable) {
                return android.R.drawable.sym_def_app_icon
            }
        }
        return icon
    }

    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (_: Throwable) {
        null
    }

    private fun mapToBundle(data: Map<String, String>): Bundle =
        Bundle().apply { data.forEach { (k, v) -> putString(k, v) } }

    private fun bundleToMap(bundle: Bundle?): Map<String, String>? {
        bundle ?: return null
        val map = HashMap<String, String>()
        for (key in bundle.keySet()) {
            bundle.getString(key)?.let { map[key] = it }
        }
        return map
    }
}

/**
 * Invisible Activity every rich-notification tap goes through: reports the `open`, then
 * opens the card target. An Activity (not a receiver or service) because Android 12+
 * blocks starting Activities from notification trampolines of any other kind.
 */
internal class RichNotificationTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            RichNotificationBuilder.onTap(this, intent)
        } catch (t: Throwable) {
            Log.w("BeAroundSDK-RichPush", "Tap handling failed: ${t.message}")
        } finally {
            finish()
        }
    }
}

/** Carousel prev/next. Re-posts the same notification id off the main thread. */
internal class RichNotificationActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RichNotificationBuilder.ACTION_CAROUSEL_NAV) return
        val pending: PendingResult? = goAsync()
        val appContext = context.applicationContext
        thread(name = "bearound-rich-nav") {
            try {
                RichNotificationBuilder.onCarouselNav(appContext, intent)
            } catch (t: Throwable) {
                Log.w("BeAroundSDK-RichPush", "Carousel navigation failed: ${t.message}")
            } finally {
                pending?.finish()
            }
        }
    }
}
