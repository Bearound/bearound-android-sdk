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
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

    /** Schemes never opened from a push tap: treated as "no target" (the tap opens the app). */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "data")

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
     * http(s): through the tracker click endpoint when [marker] is measurable, raw otherwise.
     * Any other allowed scheme (a deep link): opened directly, never through the tracker.
     */
    fun tapUrl(rawUrl: String?, index: Int, marker: PushMarker?): String? {
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty() || scheme in BLOCKED_SCHEMES) return null
        val isWeb = scheme == "http" || scheme == "https"
        if (!isWeb || marker == null) return url
        return "${trackerBase(marker)}/v1/push:click?d=${enc(marker.d)}&r=${enc(url)}&idx=$index"
    }

    /** Carousel index wrap-around: -1 is the last card, `count` is the first. */
    fun wrapIndex(index: Int, count: Int): Int {
        if (count <= 0) return 0
        return ((index % count) + count) % count
    }

    private fun trackerBase(marker: PushMarker) = marker.tr.trimEnd('/')

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** Fetches and downsamples one image. Abstracted so tests never hit the network. */
internal fun interface RichImageLoader {
    fun load(url: String, maxEdgePx: Int): Bitmap?
}

/** Default loader: short-timeout GET, capped body size, sampled decode. */
internal object HttpRichImageLoader : RichImageLoader {
    private const val TAG = "BeAroundSDK-RichPush"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val MAX_BYTES = 5 * 1024 * 1024

    override fun load(url: String, maxEdgePx: Int): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Image fetch failed (HTTP ${connection.responseCode})")
                return null
            }
            val bytes = connection.inputStream.use { readCapped(it) } ?: return null
            decodeSampled(bytes, maxEdgePx)
        } catch (t: Throwable) {
            Log.w(TAG, "Image fetch failed: ${t.message}")
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

    private fun decodeSampled(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val edge = maxOf(decoded.width, decoded.height)
        if (edge <= maxEdgePx) return decoded
        val ratio = maxEdgePx.toFloat() / edge
        return decoded.scale(
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1)
        )
    }
}

/**
 * Builds and posts the notification of a rich push (data-only FCM carrying
 * `bearound_rich`). Reached from [BeAroundSDK.handleRemoteMessage], so it works both with
 * the SDK's optional [BearoundMessagingService] and with a host service that forwards
 * messages to the public API.
 *
 * - IMAGE: `BigPictureStyle`.
 * - TWO_IMAGES: two cards side by side, one `PendingIntent` per card.
 * - CAROUSEL: one card at a time; prev/next re-post the SAME notification id with the new
 *   index through [RichNotificationActionReceiver], re-reading the cards from the intent
 *   extras (no payload re-fetch; images come from an in-memory cache or are re-downloaded).
 * - PLAY: cover image with a play glyph; tapping opens the video URL.
 *
 * Every tap goes through [RichNotificationTrampolineActivity] (an Activity, so it is
 * allowed on Android 12+), which reports the `open` with the SDK's existing open
 * measurement and then opens the card target. If an image cannot be downloaded the
 * notification degrades to title + body.
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

    private const val URI_SCHEME = "bearound-push"
    private const val SLOT_CONTENT = "content"
    private const val SLOT_PREV = "prev"
    private const val SLOT_NEXT = "next"

    private const val LARGE_EDGE_PX = 720
    private const val CARD_EDGE_PX = 480
    private const val DOWNLOAD_BUDGET_MS = 8_000L

    @Volatile
    internal var imageLoader: RichImageLoader = HttpRichImageLoader

    /** Keyed by the raw media URL, so revisiting a carousel card does not fetch (nor count a view) again. */
    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    internal fun resetForTest() {
        imageLoader = HttpRichImageLoader
        bitmapCache.evictAll()
    }

    /**
     * Entry point from `handleRemoteMessage`. Never throws. Downloads block, so on the main
     * thread (a bridge forwarding from its UI thread) the work moves to a worker thread;
     * on FCM's own worker thread it runs inline, inside the message's execution window.
     */
    fun show(context: Context, data: Map<String, String>) {
        val appContext = context.applicationContext
        val snapshot = HashMap(data)
        val work = {
            try {
                post(appContext, snapshot, notificationIdFor(snapshot), 0, System.currentTimeMillis(), true)
            } catch (t: Throwable) {
                Log.w(TAG, "Rich notification failed: ${t.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            thread(name = "bearound-rich-push") { work() }
        } else {
            work()
        }
    }

    /** Carousel prev/next: re-posts the same notification id at the requested index. */
    fun onCarouselNav(context: Context, intent: Intent) {
        val data = bundleToMap(intent.getBundleExtra(EXTRA_DATA)) ?: return
        if (!intent.hasExtra(EXTRA_NOTIFICATION_ID)) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val index = intent.getIntExtra(EXTRA_CARD_INDEX, 0)
        val whenMs = intent.getLongExtra(EXTRA_WHEN, System.currentTimeMillis())
        post(context, data, notificationId, index, whenMs, false)
    }

    /**
     * Card or notification tap, run by [RichNotificationTrampolineActivity]: reports the
     * `open` (and the implied `received`) through the SDK's existing open measurement,
     * dismisses the notification, then opens the card target or the app.
     */
    fun onTap(activity: Activity, intent: Intent) {
        try {
            BeAroundSDK.getInstance(activity.applicationContext).handleNotificationIntent(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Open report failed: ${t.message}")
        }
        if (intent.hasExtra(EXTRA_NOTIFICATION_ID)) {
            NotificationManagerCompat.from(activity).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        val target = intent.getStringExtra(EXTRA_TARGET_URL)
        if (target != null) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, target.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
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
        degradeOnImageFailure: Boolean
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
        val notification = build(context, data, notificationId, cardIndex, whenMs, degradeOnImageFailure)
            ?: return false
        manager.notify(notificationId, notification)
        return true
    }

    internal fun build(
        context: Context,
        data: Map<String, String>,
        notificationId: Int,
        cardIndex: Int,
        whenMs: Long,
        degradeOnImageFailure: Boolean
    ): Notification? {
        val title = data[KEY_TITLE].orEmpty()
        val body = data[KEY_BODY].orEmpty()
        val payload = RichPayload.parse(data[KEY_RICH])
        val marker = PushMarker.parse(data[KEY_MARKER])
        val dataBundle = mapToBundle(data)

        val builder = NotificationCompat.Builder(context, resolveChannel(context))
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        fun cardIntent(index: Int): PendingIntent {
            val target = payload?.let { RichPushUrls.tapUrl(it.cards[index].url, index, marker) }
            return tapIntent(context, dataBundle, notificationId, "card$index", target)
        }

        val openAppIntent = tapIntent(context, dataBundle, notificationId, SLOT_CONTENT, null)

        fun plain(): Notification? {
            if (title.isBlank() && body.isBlank()) return null
            // A single-card format keeps its target when it degrades (a PLAY push still
            // opens the video); multi-card formats open the app.
            val content = if (payload != null && payload.cards.size == 1) cardIntent(0) else openAppIntent
            return builder
                .setContentIntent(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .build()
        }

        if (payload == null) return plain()

        val index = RichPushUrls.wrapIndex(cardIndex, payload.cards.size)
        builder.extras.putInt(EXTRA_CARD_INDEX, index)

        return when (payload.format) {
            RichFormat.IMAGE -> {
                val bitmap = loadCards(payload, listOf(0), marker, LARGE_EDGE_PX)[0] ?: return plain()
                builder
                    .setLargeIcon(bitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    .setContentIntent(cardIntent(0))
                    .build()
            }

            RichFormat.TWO_IMAGES -> {
                val bitmaps = loadCards(payload, listOf(0, 1), marker, CARD_EDGE_PX)
                if (bitmaps.any { it == null }) return plain()
                val expanded = RemoteViews(context.packageName, R.layout.bearound_notification_two_images)
                bindHeader(expanded, title, body)
                val slots = listOf(
                    Triple(R.id.bearound_push_card_0, R.id.bearound_push_card_0_image, R.id.bearound_push_card_0_caption),
                    Triple(R.id.bearound_push_card_1, R.id.bearound_push_card_1_image, R.id.bearound_push_card_1_caption)
                )
                slots.forEachIndexed { i, (root, image, caption) ->
                    expanded.setImageViewBitmap(image, bitmaps[i])
                    bindOptionalText(expanded, caption, payload.cards[i].caption)
                    expanded.setOnClickPendingIntent(root, cardIntent(i))
                }
                decorated(builder, context, title, body, expanded)
                    .setContentIntent(openAppIntent)
                    .build()
            }

            RichFormat.CAROUSEL -> {
                val bitmap = loadCards(payload, listOf(index), marker, LARGE_EDGE_PX)[0]
                if (bitmap == null && degradeOnImageFailure) return plain()
                val count = payload.cards.size
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
                        navIntent(context, dataBundle, notificationId, SLOT_PREV, RichPushUrls.wrapIndex(index - 1, count), whenMs)
                    )
                    expanded.setOnClickPendingIntent(
                        R.id.bearound_push_carousel_next,
                        navIntent(context, dataBundle, notificationId, SLOT_NEXT, RichPushUrls.wrapIndex(index + 1, count), whenMs)
                    )
                } else {
                    expanded.setViewVisibility(R.id.bearound_push_carousel_prev, View.GONE)
                    expanded.setViewVisibility(R.id.bearound_push_carousel_next, View.GONE)
                    expanded.setViewVisibility(R.id.bearound_push_carousel_counter, View.GONE)
                }
                decorated(builder, context, title, body, expanded)
                    .setContentIntent(cardIntent(index))
                    .build()
            }

            RichFormat.PLAY -> {
                val cover = loadCards(payload, listOf(0), marker, LARGE_EDGE_PX)[0] ?: return plain()
                val expanded = RemoteViews(context.packageName, R.layout.bearound_notification_play)
                bindHeader(expanded, title, body)
                expanded.setImageViewBitmap(R.id.bearound_push_play_cover, cover)
                expanded.setOnClickPendingIntent(R.id.bearound_push_play_frame, cardIntent(0))
                decorated(builder, context, title, body, expanded)
                    .setContentIntent(cardIntent(0))
                    .build()
            }
        }
    }

    private fun decorated(
        builder: NotificationCompat.Builder,
        context: Context,
        title: String,
        body: String,
        expanded: RemoteViews
    ): NotificationCompat.Builder {
        val collapsed = RemoteViews(context.packageName, R.layout.bearound_notification_collapsed)
        bindHeader(collapsed, title, body)
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
        targetUrl: String?
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
        whenMs: Long
    ): PendingIntent {
        val intent = Intent(context, RichNotificationActionReceiver::class.java).apply {
            action = ACTION_CAROUSEL_NAV
            setData("$URI_SCHEME://$notificationId/$slot".toUri())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_CARD_INDEX, targetIndex)
            putExtra(EXTRA_WHEN, whenMs)
            putExtra(EXTRA_DATA, data)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Downloads the requested cards in parallel within [DOWNLOAD_BUDGET_MS]; a miss is null. */
    private fun loadCards(payload: RichPayload, indices: List<Int>, marker: PushMarker?, maxEdgePx: Int): List<Bitmap?> {
        fun loadOne(index: Int): Bitmap? {
            val key = payload.imageUrl(index)
            bitmapCache.get(key)?.let { return it }
            val bitmap = try {
                imageLoader.load(RichPushUrls.imageFetchUrl(payload, index, marker), maxEdgePx)
            } catch (_: Throwable) {
                null
            } ?: return null
            bitmapCache.put(key, bitmap)
            return bitmap
        }
        if (indices.size == 1) return listOf(loadOne(indices[0]))
        val results = arrayOfNulls<Bitmap>(indices.size)
        val workers = indices.mapIndexed { pos, index ->
            thread(name = "bearound-rich-image-$pos") { results[pos] = loadOne(index) }
        }
        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
        workers.forEach { it.join((deadline - System.currentTimeMillis()).coerceAtLeast(1)) }
        return results.toList()
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
