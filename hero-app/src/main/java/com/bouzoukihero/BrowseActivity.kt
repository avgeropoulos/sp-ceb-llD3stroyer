package com.bouzoukihero

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.bouzoukihero.Ui.dp
import com.bouzoukihero.Ui.label
import com.bouzoukihero.core.YouTube
import java.net.URLEncoder

/**
 * YouTube's own mobile site inside the app, for when the built-in search can't read
 * the results. Opening any video picks it.
 */
class BrowseActivity : Activity() {
    private lateinit var web: WebView
    private var picked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        col.addView(label("Tap a video to add it to Bouzouki Hero", 14f, Ui.GOLD, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
        })
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Don't start playing videos in here; picking one is enough.
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webViewClient = object : WebViewClient() {
            // YouTube's mobile site changes pages without reloading, so watch the history too.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                check(url, view.title)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                check(url, view.title)
            }
        }
        col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        val q = intent.getStringExtra(SearchActivity.EXTRA_QUERY) ?: "bouzouki"
        web.loadUrl("https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
    }

    private fun check(url: String?, title: String?) {
        if (picked || url == null) return
        if (!url.contains("/watch") && !url.contains("/shorts/")) return
        val id = YouTube.videoId(url) ?: return
        picked = true
        // The page title is often still the results page here; the menu looks it up instead.
        val t = title?.removeSuffix(" - YouTube")?.takeIf { it.isNotBlank() && !it.startsWith("YouTube") && !it.contains("search_query") }
        setResult(RESULT_OK, Intent().putExtra(SearchActivity.RESULT_ID, id).putExtra(SearchActivity.RESULT_TITLE, t))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
