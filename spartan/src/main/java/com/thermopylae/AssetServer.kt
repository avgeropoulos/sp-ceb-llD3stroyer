package com.thermopylae

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Serves the game from APK assets on the secure origin `https://appassets.androidplatform.net`
 * and blocks every other origin. [shouldInterceptRequest] runs on a WebView IO thread; the
 * page callbacks run on the UI thread.
 */
class AssetServer(
    private val assets: AssetManager,
    private val pageStarted: () -> Unit,
    private val pageFinished: () -> Unit,
    private val rendererGone: (view: WebView, crashed: Boolean) -> Boolean,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return try {
            val url = request.url
            when (val route = HostLogic.route(url.scheme, url.host, url.path, request.method)) {
                is HostLogic.Route.Asset -> open(route.name)
                is HostLogic.Route.Error -> empty(route.status, route.reason)
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
            empty(500, "Internal Server Error")
        }
    }

    private fun open(name: String): WebResourceResponse {
        val mime = HostLogic.mimeType(name)
        return try {
            WebResourceResponse(mime, HostLogic.encodingFor(mime), assets.open(name))
        } catch (e: IOException) {
            empty(HostLogic.NOT_FOUND.status, HostLogic.NOT_FOUND.reason)
        }
    }

    private fun empty(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Only the game's own origin may load in the WebView; everything else is blocked. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return !HostLogic.isOwnOrigin(url.scheme, url.host)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        pageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        pageFinished()
    }

    /** API 26+. The renderer died (crash or OOM kill): the host replaces the WebView. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        rendererGone(view, detail.didCrash())

    private companion object {
        const val TAG = "Spartan"
    }
}
