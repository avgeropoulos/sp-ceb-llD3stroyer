package com.bouzouki

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Searching YouTube from the song screen, as Chordify does: the results (thumbnail, title, channel,
 * length) in a list, songs already prepared marked ✓, "More results" for the next page. Tapping one
 * hands its video id to [onPick]. The search and the thumbnails load off the main thread.
 */
class SongSearch(private val activity: Activity, private val ui: Ui, private val prepared: (String) -> Boolean,
                 private val onPick: (YouTubeAudio.Hit) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val pictures = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Searches for [query] and shows the results; [status] gets what to say if nothing comes back. */
    fun show(query: String, status: (String) -> Unit) {
        val hits = ArrayList<YouTubeAudio.Hit>()
        var search: YouTubeAudio.Search? = null
        var loading = true
        var failed: String? = null
        val px = ui::px

        val adapter = object : BaseAdapter() {
            override fun getCount() = hits.size + 1 // the last row: loading, "More results", or what went wrong
            override fun getItem(position: Int) = hits.getOrNull(position)
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (position < hits.size) 0 else 1

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                if (position >= hits.size) return footer(convertView as? LinearLayout)
                val row = convertView as? LinearLayout ?: hitRow()
                val hit = hits[position]
                val image = row.getChildAt(0) as ImageView
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = (if (prepared(hit.id)) "✓ " else "") + hit.title
                (texts.getChildAt(1) as TextView).text = listOf(hit.channel, clock(hit.seconds)).filter { it.isNotBlank() }.joinToString("  ·  ")
                image.tag = hit.thumbnail
                val cached = hit.thumbnail?.let { thumbs.get(it) }
                image.setImageBitmap(cached)
                if (cached == null && hit.thumbnail != null) loadThumb(hit.thumbnail, image)
                return row
            }

            private fun footer(old: LinearLayout?): View {
                val row = old ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(px(12), px(14), px(12), px(14))
                    addView(ProgressBar(activity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(px(24), px(24)))
                    addView(TextView(activity).apply { setTextColor(0xFFCFE0F5.toInt()); textSize = 14f; setPadding(px(10), 0, 0, 0) })
                }
                val spinner = row.getChildAt(0)
                val text = row.getChildAt(1) as TextView
                spinner.visibility = if (loading) View.VISIBLE else View.GONE
                text.text = when {
                    loading -> if (hits.isEmpty()) "Searching YouTube…" else "Loading more…"
                    failed != null -> failed
                    hits.isEmpty() -> "Nothing found. Try other words, in Greek or English."
                    search?.hasMore == true -> "More results"
                    else -> "That's all"
                }
                return row
            }
        }

        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = null
            setBackgroundColor(NAVY)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("🔎 $query")
            .setView(list)
            .setNegativeButton("Close", null)
            .create()

        fun loadPage() {
            val s = search ?: return
            loading = true; failed = null
            adapter.notifyDataSetChanged()
            work.execute {
                val result = try { Result.success(s.next()) } catch (e: Exception) { Result.failure(e) }
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    loading = false
                    result.onSuccess { page -> hits += page.filter { h -> hits.none { it.id == h.id } } }
                    result.onFailure { e -> failed = "Couldn't search YouTube (${(e.message ?: e.javaClass.simpleName).take(100)}). Tap to try again." }
                    adapter.notifyDataSetChanged()
                }
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val hit = hits.getOrNull(position)
            when {
                hit != null -> { dialog.dismiss(); onPick(hit) }
                !loading && (failed != null || search?.hasMore == true) -> loadPage()
            }
        }
        // Scrolling to the end loads the next page by itself.
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (!loading && failed == null && hits.isNotEmpty() && search?.hasMore == true && first + visible >= total) loadPage()
            }
        })

        dialog.show()
        work.execute {
            val made = try { Result.success(YouTubeAudio.search(query)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                made.onSuccess { search = it; loadPage() }
                made.onFailure { e ->
                    loading = false
                    failed = "Couldn't search YouTube (${(e.message ?: e.javaClass.simpleName).take(100)})."
                    adapter.notifyDataSetChanged()
                    status("Couldn't search YouTube: check the internet connection.")
                }
            }
        }
    }

    private fun hitRow(): LinearLayout {
        val px = ui::px
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(6), px(10), px(6))
            minimumHeight = px(76)
            addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF15304F.toInt())
            }, LinearLayout.LayoutParams(px(112), px(63)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), 0, 0, 0)
                addView(TextView(activity).apply {
                    setTextColor(WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(activity).apply {
                    setTextColor(0xFF9FB4CC.toInt()); textSize = 12f
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun loadThumb(url: String, into: ImageView) {
        pictures.execute {
            val bytes = YouTubeAudio.image(url) ?: return@execute
            val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null } ?: return@execute
            main.post {
                thumbs.put(url, bmp)
                if (into.tag == url) into.setImageBitmap(bmp) // the row may show another result by now
            }
        }
    }

    private fun clock(seconds: Long) = if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)
}
