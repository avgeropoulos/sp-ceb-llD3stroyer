package com.mixdeck

import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemsTest {
    @Test fun noInternet() {
        for (e in listOf(UnknownHostException("www.youtube.com"), ConnectException("refused"), SocketTimeoutException("timed out"))) {
            assertEquals("No internet connection. Check Wi-Fi and try again.", Problems.explain(e), e.toString())
        }
    }

    @Test fun rateLimited() {
        assertEquals("YouTube is limiting downloads right now. Wait a few minutes and try again.",
            Problems.explain(ReCaptchaException("rate limited", "https://www.youtube.com")))
        assertEquals("YouTube is limiting downloads right now. Wait a few minutes and try again.",
            Problems.explain(IOException("HTTP 429 for https://example.com")))
    }

    @Test fun notAvailable() {
        val text = "YouTube won't let MixDeck download this one. Try another upload: switch to Videos and search again."
        assertEquals(text, Problems.explain(ContentNotAvailableException("This video is private")))
        assertEquals(text, Problems.explain(AgeRestrictedContentException("Sign in to confirm your age")))
    }

    @Test fun refused() {
        assertEquals("YouTube refused the download. Try again; if it keeps failing, pick another upload.",
            Problems.explain(IOException("Download failed (HTTP 403)")))
    }

    @Test fun looksAtTheCauses() {
        assertEquals("No internet connection. Check Wi-Fi and try again.",
            Problems.explain(RuntimeException("search failed", IOException("wrapped", UnknownHostException("music.youtube.com")))))
    }

    /** The notice puts the advice first, so a long title is what gets cut off. */
    @Test fun adviceComesBeforeTheTitle() {
        assertEquals("No internet connection. Check Wi-Fi and try again. (couldn't download \"Grillz\")",
            Problems.downloadFailed("Grillz", UnknownHostException("www.youtube.com")))
        assertEquals("Something went wrong: bad file (couldn't load \"Grillz\")", Problems.loadFailed("Grillz", IOException("bad file")))
    }

    /** The updaters don't talk about YouTube. */
    @Test fun updateServerWords() {
        assertEquals(Problems.UPDATE_SERVER, Problems.explainUpdate(IOException("HTTP 429 for https://example.com/version.json")))
        assertEquals(Problems.UPDATE_SERVER, Problems.explainUpdate(IOException("Download failed (HTTP 403)")))
        assertEquals("No internet connection. Check Wi-Fi and try again.", Problems.explainUpdate(UnknownHostException("github.com")))
        assertEquals("Something went wrong: boom", Problems.explainUpdate(IllegalStateException("boom")))
    }

    @Test fun anythingElse() {
        assertEquals("Something went wrong: boom", Problems.explain(IllegalStateException("boom")))
        assertEquals("Something went wrong: NullPointerException", Problems.explain(NullPointerException()))
    }
}
