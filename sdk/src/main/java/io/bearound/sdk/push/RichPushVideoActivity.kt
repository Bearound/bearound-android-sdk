package io.bearound.sdk.push

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.bearound.sdk.R

/**
 * Full-screen player a PLAY rich push opens on tap: plays the card's video with sound, from
 * the file the notification already downloaded when it is still cached, else streamed.
 * Framework only ([VideoView] + [MediaController]), so the SDK adds no media dependency.
 * Handles rotation itself (declared `configChanges`), so turning the phone never restarts
 * playback.
 */
internal class RichPushVideoActivity : Activity() {

    private lateinit var videoView: VideoView
    private var positionMs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(RichNotificationBuilder.EXTRA_VIDEO_URL)
        if (url == null || !RichPushUrls.isWebUrl(url)) {
            finish()
            return
        }
        positionMs = savedInstanceState?.getInt(STATE_POSITION) ?: 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Black behind the navigation bar too (edge-to-edge windows ignore navigationBarColor).
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoView = VideoView(this)
        // MATCH_PARENT both ways: VideoView then fits the video to the screen keeping its aspect.
        root.addView(
            videoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, centered(ViewGroup.LayoutParams.WRAP_CONTENT))
        val error = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = getString(R.string.bearound_rich_push_video_error)
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
        }
        root.addView(error, centered(ViewGroup.LayoutParams.MATCH_PARENT))
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.bearound_ic_push_close)
            background = null
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.bearound_rich_push_close)
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        )
        setContentView(root)
        hideSystemBars()

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener {
            progress.visibility = View.GONE
            if (positionMs > 0) videoView.seekTo(positionMs)
            videoView.start()
        }
        videoView.setOnCompletionListener { controller.show(0) }
        videoView.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Video playback failed ($what/$extra)")
            progress.visibility = View.GONE
            error.visibility = View.VISIBLE
            true // no system "Can't play this video" dialog; the close button stays
        }

        val cached = RichMediaCache.videoFile(RichMediaCache.dir(this), url)
        if (cached.isFile && cached.length() > 0) {
            videoView.setVideoPath(cached.path)
        } else {
            videoView.setVideoURI(url.toUri())
        }
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized) {
            positionMs = videoView.currentPosition
            videoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::videoView.isInitialized && positionMs > 0) {
            videoView.seekTo(positionMs)
            videoView.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::videoView.isInitialized) outState.putInt(STATE_POSITION, videoView.currentPosition)
    }

    /**
     * Hides the status bar only: hiding the navigation bar too makes some OEMs overlay a
     * system "full screen" explainer on top of the video the first time.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun centered(width: Int) =
        FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "BeAroundSDK-RichPush"
        const val STATE_POSITION = "position"
    }
}
