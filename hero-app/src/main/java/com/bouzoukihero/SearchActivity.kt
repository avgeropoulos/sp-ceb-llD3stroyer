package com.bouzoukihero

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.bouzoukihero.Ui.dp
import com.bouzoukihero.Ui.label
import com.bouzoukihero.Ui.page
import com.bouzoukihero.Ui.rounded
import com.bouzoukihero.core.YouTubeSearch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Search YouTube for a song; tapping a result adds it to the game. */
class SearchActivity : Activity() {
    private lateinit var store: Store
    private lateinit var results: LinearLayout
    private lateinit var box: EditText
    private val images = Executors.newFixedThreadPool(4)
    @Volatile private var searchId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val col = page()
        col.addView(label("SEARCH YOUTUBE", 24f, Ui.GOLD, bold = true).apply { gravity = Gravity.CENTER })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        box = EditText(this).apply {
            hint = "Song, artist or \"bouzouki solo\""
            setText(intent.getStringExtra(EXTRA_QUERY) ?: "")
            setTextColor(Ui.WHITE)
            setHintTextColor(Ui.SUB)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false
            }
        }
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("Search", 15f, Ui.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.ACCENT, 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { search() }
        })
        col.addView(row)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)
        if (box.text.isNotBlank()) search() else box.requestFocus()
    }

    override fun onDestroy() {
        images.shutdownNow()
        super.onDestroy()
    }

    private fun search() {
        val q = box.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(box.windowToken, 0)
        val id = ++searchId
        results.removeAllViews()
        results.addView(label("Searching…", 15f, Ui.SUB).apply { setPadding(dp(4), dp(12), 0, 0) })
        val key = store.apiKey
        thread(name = "search") {
            val found = try {
                Net.search(q, key)
            } catch (e: Exception) {
                runOnUiThread { if (id == searchId) message("Search didn't work: ${e.message ?: e.javaClass.simpleName}") }
                return@thread
            }
            runOnUiThread {
                if (id != searchId || isFinishing) return@runOnUiThread
                results.removeAllViews()
                if (found.isEmpty()) {
                    message(
                        if (key.isBlank()) "No results. If YouTube search keeps failing, add a YouTube API key in Settings, or paste a link instead."
                        else "No results for \"$q\"."
                    )
                }
                for (v in found) results.addView(row(v))
            }
        }
    }

    private fun message(s: String) {
        results.removeAllViews()
        results.addView(label(s, 14f, Ui.SUB).apply { setPadding(dp(4), dp(12), dp(4), 0) })
    }

    private fun row(v: YouTubeSearch.Video) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Ui.CARD)
        setPadding(dp(8), dp(8), dp(12), dp(8))
        val thumb = ImageView(this@SearchActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF000000.toInt())
        }
        addView(thumb, LinearLayout.LayoutParams(dp(128), dp(72)))
        val text = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label(v.title, 15f, Ui.WHITE, bold = true).apply { maxLines = 3 })
            val sub = listOf(v.channel, v.duration).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) addView(label(sub, 12f, Ui.SUB))
        }
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(RESULT_ID, v.id).putExtra(RESULT_TITLE, v.title))
            finish()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, 0)
        }
        images.execute {
            val bytes = Net.image(v.thumbnail) ?: return@execute
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
            runOnUiThread { thumb.setImageBitmap(bmp) }
        }
    }

    companion object {
        const val EXTRA_QUERY = "query"
        const val RESULT_ID = "videoId"
        const val RESULT_TITLE = "title"
    }
}
