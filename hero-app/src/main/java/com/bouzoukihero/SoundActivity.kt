package com.bouzoukihero

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.bouzoukihero.Ui.button
import com.bouzoukihero.Ui.dp
import com.bouzoukihero.Ui.label
import com.bouzoukihero.Ui.page
import com.bouzoukihero.Ui.rounded
import com.bouzoukihero.core.Sample
import com.bouzoukihero.core.Sampler
import com.bouzoukihero.core.Samples
import com.bouzoukihero.core.Synth
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * The bouzouki sound: record real notes from your own bouzouki (or import recordings),
 * and the built-in songs are played with them instead of the synthesizer.
 */
class SoundActivity : Activity() {
    private lateinit var store: Store
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        col = page()
        build()
    }

    private fun build() {
        col.removeAllViews()
        col.addView(label("BOUZOUKI SOUND", 26f, Ui.GOLD, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(label(
            "Record real notes from your bouzouki and the songs are played with them. " +
                "Pluck one open course at a time and let it ring for two seconds. " +
                "One note per course (C, F, A and D) is plenty; more notes sound even better.",
            14f, Ui.SUB,
        ).apply { setPadding(dp(4), dp(10), dp(4), dp(8)) })
        status = label("", 15f, Ui.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        col.addView(status)

        val samples = store.samples()
        if (samples.isEmpty()) {
            status.text = "Using the synthesized bouzouki"
        } else {
            status.text = "Playing with ${samples.size} real note${if (samples.size == 1) "" else "s"}"
            for (s in samples) col.addView(sampleRow(s))
        }
        col.addView(button("●  Record a note", Ui.RED) { record() })
        col.addView(button("Import a recording", Ui.CARD) { import() })
        if (samples.isNotEmpty()) col.addView(button("▶  Listen to a scale", Ui.ACCENT) { listen(samples) })
        col.addView(label(
            "Imported files should be a single note (WAV, MP3, M4A or OGG). Only the first note in the file is used.",
            12f, Ui.SUB,
        ).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        col.addView(button("Back", Ui.CARD) { finish() })
    }

    private fun sampleRow(s: Sample) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Ui.CARD)
        setPadding(dp(16), dp(8), dp(8), dp(8))
        val cents = Math.round((s.root - Math.round(s.root)) * 100)
        addView(label("${s.name}   ${if (cents >= 0) "+" else ""}$cents cents", 16f, Ui.WHITE, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("▶", 20f, Ui.GOLD, bold = true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { Ui.play(s.pcm) }
        })
        addView(label("✕", 18f, Ui.SUB, bold = true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { store.deleteSample(s); build() }
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, 0)
        }
    }

    // ---- Recording ----

    private fun record() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        busy = true
        status.text = "Get ready…"
        thread(name = "record") {
            val rate = 44100
            val seconds = 3.5
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            } catch (e: SecurityException) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                done(null, "Couldn't open the microphone.")
                return@thread
            }
            val pcm = ShortArray((rate * seconds).toInt())
            try {
                rec.startRecording()
                // Let the mic settle, then ask for the pluck.
                val warm = ShortArray(rate / 4)
                rec.read(warm, 0, warm.size)
                runOnUiThread { status.text = "Pluck a string now!" }
                var got = 0
                while (got < pcm.size) {
                    val n = rec.read(pcm, got, pcm.size - got)
                    if (n <= 0) break
                    got += n
                }
            } finally {
                rec.stop()
                rec.release()
            }
            process(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) record()
            else status.text = "The microphone is needed to record your bouzouki."
        }
    }

    private fun process(raw: FloatArray, rate: Int) {
        try {
            val s = Samples.prepare(raw, rate)
            store.saveSample(s)
            done(s, null)
        } catch (e: Samples.NoNote) {
            done(null, e.message)
        } catch (e: Exception) {
            done(null, "Something went wrong: ${e.message}")
        }
    }

    private fun done(s: Sample?, error: String?) = runOnUiThread {
        busy = false
        if (isFinishing) return@runOnUiThread
        build()
        if (s != null) {
            status.text = "Got it: ${s.name}"
            Ui.play(s.pcm)
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("No note recorded")
                .setMessage(error)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---- Importing ----

    private fun import() {
        if (busy) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try {
            startActivityForResult(i, REQ_FILE)
        } catch (_: Exception) {
            status.text = "No file picker on this phone."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || uri == null) return
        busy = true
        status.text = "Reading the recording…"
        thread(name = "import") {
            try {
                val (pcm, rate) = decode(uri, 8.0)
                process(pcm, rate)
            } catch (e: Exception) {
                done(null, "Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Decodes the start of an audio file to mono floats with Android's own decoders. */
    private fun decode(uri: Uri, maxSeconds: Double): Pair<FloatArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(this, uri, null)
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("no audio in it")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var out = FloatArray(rate * 4)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = shorts.remaining() / channels
                        if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += shorts.get(f * channels + c) / 32768f
                            out[n++] = s / channels
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n >= rate * maxSeconds) break
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return out.copyOf(n) to rate
        } finally {
            ex.release()
        }
    }

    private fun listen(samples: List<Sample>) {
        val sampler = Sampler(samples)
        // D Hitzaz, up and back down.
        val scale = intArrayOf(62, 63, 66, 67, 69, 70, 72, 74, 72, 70, 69, 67, 66, 63, 62)
        val step = 0.32
        val out = FloatArray(((scale.size * step + 1.5) * Synth.RATE).toInt())
        for ((i, m) in scale.withIndex()) sampler.note(out, i * step, m, if (i == scale.size - 1) 1.5 else step + 0.1, 0.36f)
        val peak = out.maxOf { Math.abs(it) }
        if (peak > 0.95f) for (i in out.indices) out[i] *= 0.95f / peak
        Ui.play(out)
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_FILE = 2
    }
}
