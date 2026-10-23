package com.bouzoukihero

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Plays a YouTube video with the official embedded player (IFrame Player API) in a
 * [WebView], and follows its play position so the notes stay in time with it.
 *
 * The page reports the video's time every 50 ms; between reports the clock runs on
 * the phone's own timer, so notes still move smoothly.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeSong(
    private val web: WebView,
    videoId: String,
    private val startAt: Double,
    private val stopAt: Double?,
    private val events: Events,
) : SongClock {
    interface Events {
        /** The video has started playing for the first time. */
        fun onPlaying()
        fun onVideoError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reportedTime = startAt
    @Volatile private var reportedAt = SystemClock.elapsedRealtimeNanos()
    @Volatile private var playing = false
    @Volatile private var videoEnded = false
    @Volatile private var startedOnce = false
    @Volatile private var lastOut = 0.0

    init {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF000000.toInt())
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Hero")
        // An https base URL gives the player a Referer identifying this app, which the
        // embedded player requires.
        web.loadDataWithBaseURL("$ORIGIN/", page(videoId, startAt), "text/html", "utf-8", null)
        main.postDelayed({
            if (!startedOnce) events.onVideoError("The video didn't start. Check your internet connection and try again.")
        }, 25_000)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onState(state: Int, time: Double) {
            reportedTime = time
            reportedAt = SystemClock.elapsedRealtimeNanos()
            playing = state == 1
            if (state == 0) videoEnded = true
            if (state == 1 && !startedOnce) {
                startedOnce = true
                main.post { events.onPlaying() }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            val msg = when (code) {
                2 -> "That YouTube link doesn't point to a video."
                5 -> "This video can't be played here."
                100 -> "This video was removed or is private."
                101, 150, 152 -> "The uploader doesn't allow this video to play inside other apps. Try another upload of the same song."
                153 -> "YouTube refused to play the video here (error 153)."
                else -> "YouTube couldn't play this video (error $code)."
            }
            main.post { events.onVideoError(msg) }
        }
    }

    override fun now(): Double {
        var t = reportedTime
        if (playing) t += ((SystemClock.elapsedRealtimeNanos() - reportedAt) / 1e9).coerceAtMost(0.4)
        // Don't let small reporting jitter move the notes backwards.
        if (t < lastOut && lastOut - t < 0.08) t = lastOut
        lastOut = t
        return t
    }

    override val ended: Boolean
        get() = videoEnded || (stopAt != null && reportedTime >= stopAt)

    private fun js(code: String) = main.post { web.evaluateJavascript("try{$code}catch(e){}", null) }

    override fun pause() {
        playing = false
        js("player.pauseVideo()")
    }

    override fun resume() = js("player.playVideo()")

    override fun restart() {
        videoEnded = false
        lastOut = 0.0
        reportedTime = startAt
        reportedAt = SystemClock.elapsedRealtimeNanos()
        js("player.seekTo($startAt, true); player.playVideo()")
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        main.post {
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    private fun page(videoId: String, start: Double) = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
        #player{position:absolute;left:0;top:0;width:100%;height:100%}</style>
        </head><body><div id="player"></div>
        <script>
        var player = null, startAt = $start;
        var tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        document.head.appendChild(tag);
        function report() {
          if (player && player.getCurrentTime) Hero.onState(player.getPlayerState(), player.getCurrentTime());
        }
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            width: '100%', height: '100%', videoId: '$videoId',
            playerVars: { playsinline: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3,
                          autoplay: 1, start: Math.floor(startAt), origin: '$ORIGIN' },
            events: {
              onReady: function(e) { if (startAt > 0) e.target.seekTo(startAt, true); e.target.playVideo(); report(); },
              onStateChange: function(e) { report(); },
              onError: function(e) { Hero.onError(e.data); }
            }
          });
        }
        setInterval(report, 50);
        </script></body></html>
    """.trimIndent()

    companion object {
        const val ORIGIN = "https://com.bouzoukihero"
    }
}
