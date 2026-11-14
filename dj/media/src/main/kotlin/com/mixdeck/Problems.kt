package com.mixdeck

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns what went wrong (a download, a search, a load) into plain words for the notice line. */
object Problems {
    const val NO_INTERNET = "No internet connection. Check Wi-Fi and try again."
    const val RATE_LIMITED = "YouTube is limiting downloads right now. Wait a few minutes and try again."
    const val NOT_AVAILABLE = "YouTube won't let MixDeck download this one. Try another upload: switch to Videos and search again."
    const val REFUSED = "YouTube refused the download. Try again; if it keeps failing, pick another upload."

    /** One sentence (or two) a non-technical person can act on. Looks through the whole cause chain. */
    fun explain(e: Throwable): String {
        var t: Throwable? = e
        val seen = HashSet<Throwable>()
        while (t != null && seen.add(t)) {
            val msg = t.message ?: ""
            when {
                t is UnknownHostException || t is ConnectException || t is SocketTimeoutException ||
                    t is NoRouteToHostException -> return NO_INTERNET
                t is ReCaptchaException || msg.contains("HTTP 429") -> return RATE_LIMITED
                t is ContentNotAvailableException -> return NOT_AVAILABLE
                msg.contains("HTTP 403") -> return REFUSED
            }
            t = t.cause
        }
        val what = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "Something went wrong: $what"
    }

    /**
     * The notice when a picked song couldn't be downloaded: what to do first, the song last, so a
     * long title is what gets cut off on the notice line, not the advice.
     */
    fun downloadFailed(title: String, e: Throwable) = "${explain(e)} (couldn't download \"$title\")"

    /** The notice when a saved song couldn't be loaded onto a deck (advice first, as [downloadFailed]). */
    fun loadFailed(title: String, e: Throwable) = "${explain(e)} (couldn't load \"$title\")"

    /** For the updaters: the update server refused or limited us (YouTube's words don't fit there). */
    const val UPDATE_SERVER = "The update server didn't answer (it may be busy)"

    /** [explain] for checking for and downloading an update. */
    fun explainUpdate(e: Throwable): String = explain(e).let {
        if (it == RATE_LIMITED || it == REFUSED || it == NOT_AVAILABLE) UPDATE_SERVER else it
    }
}
