package com.mixdeck.ui

import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Deck
import com.mixdeck.engine.FxType
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.TrackAnalysis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole DJ console, drawn on one Canvas: two decks with jog wheels, scrolling waveforms,
 * a two-channel mixer, crossfader and sampler. Multi-touch, so you can work both decks at once.
 * [computer]: drawn in the desktop app (mouse and keyboard: "click", key caps), else on a phone.
 */
class Console(private val mixer: Mixer, private val host: Host, val computer: Boolean = false) {

    interface Host {
        fun openLibrary(deck: Int)
        fun deckMenu(deck: Int)
        fun mainMenu()
        fun toggleRecord()
        fun recording(): Boolean
        fun recordSeconds(): Double
        fun loadingText(deck: Int): String?
        /** Text shown on the REC button while a finished mix is being saved, or null. */
        fun savingText(): String?
        /** NEXT was tapped: show songs that fit after [next].after's song; load the pick onto [next].target. */
        fun suggestNext(next: NextPlan) = openLibrary(next.target)
        /** A newly loaded song on [deck] has started playing (e.g. remember it as played). */
        fun deckStarted(deck: Int) {}
        /** A new version of MixDeck is ready: the ☰ button shows a badge (the menu offers it). */
        fun updateReady(): Boolean = false
        /** Start the guided tutorial (the Start card's "Learn with a guided mix"). */
        fun startTutorial() {}
        /** The tutorial couldn't get its songs and the user tapped "Try again". */
        fun retryTutorialSongs() {}
        /** 🎧 was turned on while split cue is off: offer to turn split cue on. */
        fun splitCueOff() {}
        /** Open the full help guide (tapped from the labels banner). */
        fun openGuide() {}
        /**
         * Title of the song queued to load on [deck] when it stops, or of one downloading for it
         * while it plays ("Grillz · downloading 42%"), or null. Shown as "Up next: …" on its strip.
         */
        fun upNext(deck: Int): String? = null
        /** A setting changed on the console (e.g. the MIX length): save the settings. */
        fun settingsChanged() {}
        /** Stop recording and save the mix (REC held while recording). */
        fun stopRecord() { toggleRecord() }
        /** The keyboard key that does the same as the control named [key] (computer only), or null. */
        fun shortcut(key: String): String? = null
    }

    private val colA = 0xFF29B6F6.toInt()
    private val colB = 0xFFFF7043.toInt()
    private val bg = 0xFF0E0E11.toInt()
    private val panel = 0xFF1A1A1F.toInt()
    private val btnBg = 0xFF2A2A31.toInt()
    private val dim = 0xFF8A8A96.toInt()
    private val hotCueColors = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONO_BOLD }
    private val wave = Paint().apply { strokeWidth = 2f }

    private val ctls = ArrayList<Ctl>()
    private val active = HashMap<Int, Ctl>()
    private var radius = 8f
    private var unit = 10f

    fun deckColor(i: Int) = if (i == 0) colA else colB
    /** "Tap" on a phone, "Click" on a computer. */
    private val tapCap get() = if (computer) "Click" else "Tap"
    private val tap get() = if (computer) "click" else "tap"
    private inline fun act(crossinline f: Mixer.() -> Unit) = synchronized(mixer.lock) { mixer.f() }
    private fun now() = System.nanoTime() / 1_000_000

    var width = 0; private set
    var height = 0; private set

    // ------------------------------------------------------------------ controls

    private fun <T : Ctl> T.h(s: String): T { help = s; return this }
    private fun <T : Ctl> T.k(s: String): T { key = s; return this }

    /** When true, every control is labelled with what it does (the "key"). */
    var helpMode = false
    /** The labels banner and the notice line: between the two song strips. */
    internal val keyBanner = RectF()

    /** First run: no song was ever loaded (the Start card welcomes a newcomer). */
    var newcomer = false

    // ------------------------------------------------------------------ notice line

    private class Notice(val text: String, val deck: Int, val error: Boolean, val until: Long)

    /** The current notice; one immutable object, so any thread can replace it. */
    @Volatile private var notice: Notice? = null

    /**
     * Shows [text] on the console's notice line (between the two song strips) for [ms]
     * milliseconds, edged in [deck]'s colour (-1: neutral), or red for an [error] (which stays at
     * least 8 seconds). A new notice replaces the old one. Safe to call from any thread.
     */
    fun say(text: String, deck: Int = -1, error: Boolean = false, ms: Long = 4000) {
        notice = Notice(text, deck, error, now() + if (error) maxOf(ms, 8000L) else ms)
    }

    /** The notice showing now, or null. */
    val noticeText: String? get() = notice?.takeIf { now() < it.until }?.text

    /** The guided tutorial being shown, if any. */
    var tutorial: Tutorial? = null
        set(v) { field = v; helpMode = false }
    private val tutBack = RectF(); internal val tutNext = RectF(); private val tutExit = RectF()
    /** Step 1's right-hand button while the songs are on their way ("Getting songs…", or "Try again"). */
    internal val tutWait = RectF(); private val tutRetry = RectF()
    private val tutHoles = ArrayList<RectF>()

    abstract inner class Ctl(val r: RectF) {
        /** Short label shown on this control in the help key. */
        var help: String? = null
        /** Name the tutorial uses to point at this control. */
        var key: String? = null
        open fun down(x: Float, y: Float) {}
        open fun move(x: Float, y: Float) {}
        open fun up() {}
        /** The touch was taken away (not a real release): no tap. */
        open fun cancel() = up()
        /** Mouse wheel over the control ([notches] > 0 = up). */
        open fun wheel(notches: Float) {}
        abstract fun draw(c: Canvas)
    }

    inner class Btn(
        r: RectF, private val label: () -> String, private val color: Int,
        private val lit: () -> Boolean = { false }, private val onUp: (() -> Unit)? = null,
        private val onLong: (() -> Unit)? = null, private val textScale: Float = 1f,
        /** Overrides [color] while drawing, e.g. NEXT takes the colour of the deck it loads. */
        private val colorOf: (() -> Int)? = null,
        /** Shows a small dot in the corner, e.g. ☰ when an update is ready. */
        private val badge: (() -> Boolean)? = null,
        /** Run the action ([onDown]) when the finger lifts, unless it was a long press ([onLong]). */
        private val tapOnUp: Boolean = false,
        /** 0..1: a progress strip along the bottom (e.g. MIX while it blends), or null for none. */
        private val progress: (() -> Float?)? = null,
        private val onDown: () -> Unit,
    ) : Ctl(r) {
        private var pressed = false; private var downAt = 0L; private var longDone = false
        /** The finger slid off before lifting: a [tapOnUp] button then does nothing, like any button. */
        private var slidOff = false
        /** Pressed now and not yet a long press (e.g. REC says "HOLD" meanwhile). */
        val held get() = pressed && !longDone && !slidOff
        override fun down(x: Float, y: Float) { pressed = true; downAt = now(); longDone = false; slidOff = false; if (!tapOnUp) onDown() }
        override fun move(x: Float, y: Float) { if (tapOnUp && !grow(r).contains(x, y)) slidOff = true }
        override fun up() {
            val wasPressed = pressed
            pressed = false
            onUp?.invoke()
            if (!tapOnUp || !wasPressed || longDone || slidOff) return
            // Held long enough but not drawn meanwhile (the long press wasn't noticed yet).
            if (onLong != null && now() - downAt > 650) { longDone = true; onLong.invoke() } else onDown()
        }
        override fun cancel() { pressed = false; onUp?.invoke() }
        override fun draw(c: Canvas) {
            if (pressed && !longDone && !slidOff && onLong != null && now() - downAt > 650) { longDone = true; onLong.invoke() }
            val on = lit()
            val color = colorOf?.invoke() ?: color
            fill.color = if (on) color else btnBg
            if (pressed) fill.color = blend(fill.color, Color.WHITE, 0.25f)
            c.drawRoundRect(r, radius, radius, fill)
            if (!on) { stroke.color = color; stroke.strokeWidth = unit * 0.12f; c.drawRoundRect(inset(r, unit * 0.06f), radius, radius, stroke) }
            text.color = if (on) Color.BLACK else Color.WHITE
            text.textSize = min(r.height() * 0.42f, unit * 1.35f) * textScale
            val s = label()
            val p = progress?.invoke()
            if (!drawIcon(c, s, r, text.color)) {
                fitText(s, r.width() * 0.9f)
                // Nudged up, clear of the progress strip when there is one.
                val y = r.centerY() - (if (p != null) r.height() * 0.07f else 0f)
                c.drawText(s, r.centerX(), y - (text.descent() + text.ascent()) / 2, text)
            }
            if (p != null) {
                // Inside the bottom 18%: a dark track and a white fill up to [p].
                val h = r.height() * 0.18f; val m = unit * 0.25f
                val track = RectF(r.left + m, r.bottom - h, r.right - m, r.bottom - m)
                fill.color = 0x59000000; c.drawRoundRect(track, radius * 0.4f, radius * 0.4f, fill)
                fill.color = Color.WHITE
                c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * p.coerceIn(0f, 1f), track.bottom),
                    radius * 0.4f, radius * 0.4f, fill)
            }
            if (badge?.invoke() == true) {
                val d = min(r.width(), r.height()) * 0.13f
                fill.color = 0xFF00E676.toInt()
                c.drawCircle(r.right - d * 1.6f, r.top + d * 1.6f, d, fill)
            }
        }
    }

    inner class Knob(
        r: RectF, private val label: String, private val color: Int,
        private val get: () -> Float, private val set: (Float) -> Unit,
        private val min: Float = -1f, private val max: Float = 1f, private val def: Float = 0f,
    ) : Ctl(r) {
        private var lastY = 0f; private var lastTap = 0L
        override fun down(x: Float, y: Float) {
            if (now() - lastTap < 300) act { set(def) }
            lastTap = now(); lastY = y
        }
        override fun wheel(notches: Float) {
            val old = get()
            var v = old + notches * 0.05f * (max - min)
            if ((old - def) * (v - def) < 0) v = def
            act { set(v.coerceIn(min, max)) }
        }
        override fun move(x: Float, y: Float) {
            val dy = lastY - y; lastY = y
            val old = get()
            var v = old + dy / (unit * 14f) * (max - min)
            // Small detent at the centre so it's easy to get back to flat.
            if ((old - def) * (v - def) < 0 || (old == def && abs(v - def) < (max - min) * 0.03f)) v = def
            act { set(v.coerceIn(min, max)) }
        }
        override fun draw(c: Canvas) {
            val labelH = r.height() * 0.24f
            val cx = r.centerX(); val cy = r.top + (r.height() - labelH) / 2
            val rad = min(r.width(), r.height() - labelH) * 0.42f
            val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
            stroke.strokeWidth = rad * 0.22f
            stroke.color = btnBg
            c.drawArc(oval, 135f, 270f, false, stroke)
            val t0 = (def - min) / (max - min); val t = (get() - min) / (max - min)
            stroke.color = color
            c.drawArc(oval, 135f + 270f * minOf(t0, t), 270f * abs(t - t0) + 0.5f, false, stroke)
            val ang = Math.toRadians((135f + 270f * t).toDouble())
            fill.color = 0xFF3A3A44.toInt()
            c.drawCircle(cx, cy, rad * 0.72f, fill)
            stroke.color = Color.WHITE; stroke.strokeWidth = rad * 0.13f
            c.drawLine(cx + cos(ang).toFloat() * rad * 0.2f, cy + sin(ang).toFloat() * rad * 0.2f,
                cx + cos(ang).toFloat() * rad * 0.68f, cy + sin(ang).toFloat() * rad * 0.68f, stroke)
            text.color = dim; text.textSize = labelH * 0.75f
            c.drawText(label, cx, r.bottom - labelH * 0.15f, text)
        }
    }

    /** A fader. Vertical unless [horizontal]. With [invert], top/left is the maximum. */
    inner class Fader(
        r: RectF, private val color: Int, private val get: () -> Float, private val set: (Float) -> Unit,
        private val min: Float, private val max: Float, private val def: Float,
        private val horizontal: Boolean = false, private val invert: Boolean = false,
        private val caption: (() -> String)? = null,
    ) : Ctl(r) {
        private var last = 0f; private var lastTap = 0L
        private val len get() = if (horizontal) r.width() * 0.88f else r.height() * 0.84f
        override fun down(x: Float, y: Float) {
            if (now() - lastTap < 300) act { set(def) }
            lastTap = now(); last = if (horizontal) x else y
        }
        override fun wheel(notches: Float) {
            var d = notches * 0.04f * (max - min)
            if (horizontal) d = -d
            if (invert) d = -d
            act { set((get() + d).coerceIn(min, max)) }
        }
        override fun move(x: Float, y: Float) {
            val p = if (horizontal) x else y
            var d = (p - last) / len * (max - min); last = p
            if (!horizontal) d = -d
            if (invert) d = -d
            act { set((get() + d).coerceIn(min, max)) }
        }
        override fun draw(c: Canvas) {
            var t = (get() - min) / (max - min)
            if (invert) t = 1 - t
            stroke.color = 0xFF050507.toInt(); stroke.strokeWidth = unit * 0.5f
            if (horizontal) {
                val y = r.centerY(); val x0 = r.centerX() - len / 2
                c.drawLine(x0, y, x0 + len, y, stroke)
                stroke.color = dim; stroke.strokeWidth = unit * 0.1f
                for (k in 0..8) { val x = x0 + len * k / 8; c.drawLine(x, y - r.height() * 0.32f, x, y - r.height() * 0.22f, stroke) }
                val x = x0 + len * t
                val cap = RectF(x - unit * 1.1f, r.top + r.height() * 0.12f, x + unit * 1.1f, r.bottom - r.height() * 0.12f)
                fill.color = 0xFFDDDDE3.toInt(); c.drawRoundRect(cap, radius * 0.6f, radius * 0.6f, fill)
                fill.color = color; c.drawRect(x - unit * 0.12f, cap.top, x + unit * 0.12f, cap.bottom, fill)
            } else {
                val x = r.centerX(); val y0 = r.centerY() + len / 2
                c.drawLine(x, y0, x, y0 - len, stroke)
                stroke.color = dim; stroke.strokeWidth = unit * 0.1f
                for (k in 0..10) { val y = y0 - len * k / 10; c.drawLine(r.left + r.width() * 0.08f, y, r.left + r.width() * 0.22f, y, stroke) }
                val y = y0 - len * t
                val capH = minOf(unit * 2.2f, r.height() * 0.12f)
                val cap = RectF(r.left + r.width() * 0.18f, y - capH / 2, r.right - r.width() * 0.18f, y + capH / 2)
                fill.color = 0xFFDDDDE3.toInt(); c.drawRoundRect(cap, radius * 0.6f, radius * 0.6f, fill)
                fill.color = color; c.drawRect(cap.left, y - unit * 0.12f, cap.right, y + unit * 0.12f, fill)
            }
            caption?.let {
                text.color = Color.WHITE; text.textSize = unit * 1.0f
                if (horizontal) c.drawText(it(), r.centerX(), r.top - unit * 0.3f, text)
                else c.drawText(it(), r.centerX(), r.bottom + unit * 1.1f, text)
            }
        }
    }

    inner class Jog(r: RectF, private val i: Int) : Ctl(r) {
        private val d: Deck get() = mixer.decks[i]
        private var lastA = 0.0
        private var ring = false
        private val rad get() = min(r.width(), r.height()) / 2 * 0.96f
        /** This touch opened the library (an empty deck's LOAD disc): no scratching. */
        private var loadTap = false
        override fun down(x: Float, y: Float) {
            loadTap = leadsToLoad(i) && hypot(x - r.centerX(), y - r.centerY()) < rad * 0.3f
            if (loadTap) { host.openLibrary(i); return }
            lastA = atan2((y - r.centerY()).toDouble(), (x - r.centerX()).toDouble())
            ring = hypot(x - r.centerX(), y - r.centerY()) > rad * 0.8f
            if (!ring) act { decks[i].jogTouch(true) }
        }
        override fun wheel(notches: Float) { act { decks[i].jogMove(notches * 0.02) } }
        override fun move(x: Float, y: Float) {
            if (loadTap) return
            val a = atan2((y - r.centerY()).toDouble(), (x - r.centerX()).toDouble())
            var da = a - lastA
            if (da > PI) da -= 2 * PI; if (da < -PI) da += 2 * PI
            lastA = a
            act { decks[i].jogMove(da / (2 * PI)) }
        }
        override fun up() { if (loadTap) loadTap = false else if (!ring) act { decks[i].jogTouch(false) } }
        override fun draw(c: Canvas) {
            val cx = r.centerX(); val cy = r.centerY(); val rr = rad
            val col = deckColor(i)
            fill.color = 0xFF050507.toInt(); c.drawCircle(cx, cy, rr, fill)
            stroke.color = if (d.scratching) Color.WHITE else col; stroke.strokeWidth = rr * 0.05f
            c.drawCircle(cx, cy, rr * 0.9f, stroke)
            stroke.color = 0xFF202028.toInt(); stroke.strokeWidth = 1.5f
            for (k in 1..5) c.drawCircle(cx, cy, rr * (0.3f + k * 0.1f), stroke)
            // Progress ring: how far through the track.
            if (d.loaded && d.durationSec > 0) {
                val p = (d.positionSec / d.durationSec).toFloat().coerceIn(0f, 1f)
                stroke.color = blend(col, bg, 0.45f); stroke.strokeWidth = rr * 0.035f
                val o = RectF(cx - rr * 0.97f, cy - rr * 0.97f, cx + rr * 0.97f, cy + rr * 0.97f)
                c.drawArc(o, -90f, 360f * p, false, stroke)
            }
            // Rotating marker: one turn every 1.8 s of audio, like a record at 33 RPM.
            val ang = d.positionSec / 1.8 * 2 * PI - PI / 2
            stroke.color = Color.WHITE; stroke.strokeWidth = rr * 0.05f
            c.drawLine(cx + cos(ang).toFloat() * rr * 0.32f, cy + sin(ang).toFloat() * rr * 0.32f,
                cx + cos(ang).toFloat() * rr * 0.84f, cy + sin(ang).toFloat() * rr * 0.84f, stroke)
            fill.color = col; c.drawCircle(cx, cy, rr * 0.3f, fill)
            text.color = Color.BLACK
            if (d.bpm > 0) {
                // BPM, and under it the key you hear (moved by the tempo slider: no key lock).
                val key = KeyDisplay.label(d)
                val up = if (key != null) rr * 0.045f else 0f
                text.textSize = rr * 0.17f
                c.drawText("%.1f".format(d.effectiveBpm), cx, cy + rr * 0.02f - up, text)
                text.textSize = rr * 0.09f
                c.drawText("BPM", cx, cy + rr * 0.15f - up, text)
                if (key != null) { text.textSize = rr * 0.085f; c.drawText(key, cx, cy + rr * 0.215f, text) }
            } else {
                text.textSize = rr * 0.11f
                c.drawText(if (d.loaded) "NO BEAT" else "LOAD", cx, cy + rr * 0.04f, text)
            }
            // Beat phase dots (4 beats).
            if (d.bpm > 0) {
                val b = ((d.beatIndex() % 4 + 4) % 4).toInt()
                for (k in 0..3) {
                    fill.color = if (k == b && d.playing) Color.WHITE else 0xFF33333C.toInt()
                    c.drawCircle(cx + (k - 1.5f) * rr * 0.11f, cy + rr * 0.48f, rr * 0.035f, fill)
                }
            }
        }
    }

    /** Scrolling close-up waveform with the beat grid. Drag it to scratch or move through the track. */
    inner class Lane(r: RectF, private val i: Int) : Ctl(r) {
        private val d: Deck get() = mixer.decks[i]
        private var lastX = 0f
        private val pxPerSec get() = r.width() / 7f / (1 + d.pitch).toFloat()
        private val lo = FloatArray(4096); private val mid = FloatArray(4096); private val hi = FloatArray(4096)
        /** This touch opened the library (the deck was empty): no scratching. */
        private var loadTap = false
        override fun down(x: Float, y: Float) {
            loadTap = leadsToLoad(i)
            if (loadTap) { host.openLibrary(i); return }
            lastX = x; act { decks[i].jogTouch(true, forceScratch = true) }
        }
        override fun move(x: Float, y: Float) {
            if (loadTap) return
            val dx = x - lastX; lastX = x
            act { decks[i].jogMove(-dx / pxPerSec / 1.8) }
        }
        override fun up() { if (loadTap) loadTap = false else act { decks[i].jogTouch(false) } }
        override fun draw(c: Canvas) {
            fill.color = 0xFF08080A.toInt(); c.drawRect(r, fill)
            val a = d.analysis
            val col = deckColor(i)
            if (a == null || !d.loaded) { drawEmpty(c, i); return }
            val pps = pxPerSec
            val pos = d.positionSec
            val cx = r.centerX(); val cy = r.centerY(); val half = r.height() / 2 * 0.92f
            c.save(); c.clipRect(r)
            // Loop region.
            if (d.loopIn >= 0 && d.loopOut > d.loopIn) {
                val x0 = cx + ((d.loopIn / d.trackRate - pos) * pps).toFloat()
                val x1 = cx + ((d.loopOut / d.trackRate - pos) * pps).toFloat()
                fill.color = if (d.loopOn) 0x5532CD32 else 0x22888888
                c.drawRect(x0, r.top, x1, r.bottom, fill)
            }
            // Waveform: bass-heavy bins in the deck colour, mids white-ish, treble light blue.
            var nl = 0; var nm = 0; var nh = 0
            var x = r.left
            while (x < r.right && nl < lo.size - 4 && nm < mid.size - 4 && nh < hi.size - 4) {
                val t = pos + (x - cx) / pps
                val b = (t * a.binsPerSecond).toInt()
                if (b >= 0 && b < a.peak.size) {
                    val h = a.peak[b] * half
                    val arr: FloatArray; val n: Int
                    when {
                        a.bass[b] > 0.55f -> { arr = lo; n = nl; nl += 4 }
                        a.treble[b] > 0.35f -> { arr = hi; n = nh; nh += 4 }
                        else -> { arr = mid; n = nm; nm += 4 }
                    }
                    arr[n] = x; arr[n + 1] = cy - h; arr[n + 2] = x; arr[n + 3] = cy + h
                }
                x += 2f
            }
            wave.color = col; c.drawLines(lo, 0, nl, wave)
            wave.color = 0xFFE0E0E8.toInt(); c.drawLines(mid, 0, nm, wave)
            wave.color = 0xFF80DEEA.toInt(); c.drawLines(hi, 0, nh, wave)
            // Beat grid.
            if (d.bpm > 0) {
                val beat = 60.0 / d.bpm
                val tLeft = pos - cx / pps; val tRight = pos + (r.right - cx) / pps
                var k = floor((tLeft - a.firstBeatSec) / beat).toLong()
                while (true) {
                    val t = a.firstBeatSec + k * beat
                    if (t > tRight) break
                    val bx = cx + ((t - pos) * pps).toFloat()
                    val bar = Math.floorMod(k, 4L) == 0L
                    fill.color = if (bar) 0xCCFFFFFF.toInt() else 0x55FFFFFF
                    c.drawRect(bx - (if (bar) 1.5f else 0.75f), r.top, bx + (if (bar) 1.5f else 0.75f), r.top + r.height() * (if (bar) 0.22f else 0.12f), fill)
                    c.drawRect(bx - 0.75f, r.bottom - r.height() * 0.12f, bx + 0.75f, r.bottom, fill)
                    k++
                }
            }
            // Cue and hot cue markers.
            fun marker(frame: Double, color: Int, label: String) {
                val mx = cx + ((frame / d.trackRate - pos) * pps).toFloat()
                if (mx < r.left - 20 || mx > r.right + 20) return
                fill.color = color
                c.drawRect(mx - 1.5f, r.top, mx + 1.5f, r.bottom, fill)
                val s = unit * 0.9f
                c.drawRect(mx, r.top, mx + s * 1.2f, r.top + s * 1.2f, fill)
                text.color = Color.BLACK; text.textSize = s
                c.drawText(label, mx + s * 0.6f, r.top + s, text)
            }
            marker(d.cuePoint, 0xFFFFA000.toInt(), "C")
            for (h in 0..3) if (!d.hotCues[h].isNaN()) marker(d.hotCues[h], hotCueColors[h], "${h + 1}")
            c.restore()
            fill.color = Color.WHITE; c.drawRect(cx - 1.5f, r.top, cx + 1.5f, r.bottom, fill)
            // Deck letter.
            text.color = col; text.textSize = r.height() * 0.3f
            c.drawText(if (i == 0) "A" else "B", r.left + r.height() * 0.25f, r.top + r.height() * 0.36f, text)
        }
    }

    /** The whole track at a glance. Tap to jump. */
    inner class Overview(r: RectF, private val i: Int) : Ctl(r) {
        private val d: Deck get() = mixer.decks[i]
        private var cacheFor: TrackAnalysis? = null
        private var bars = FloatArray(0)
        override fun down(x: Float, y: Float) {
            act { decks[i].seekFraction(((x - r.left) / r.width()).toDouble()) }
        }
        override fun draw(c: Canvas) {
            fill.color = 0xFF08080A.toInt(); c.drawRoundRect(r, radius * 0.5f, radius * 0.5f, fill)
            val a = d.analysis ?: return
            if (cacheFor !== a) {
                cacheFor = a
                val cols = (r.width() / 2).toInt().coerceAtLeast(1)
                bars = FloatArray(cols)
                val per = a.peak.size.toFloat() / cols
                for (k in 0 until cols) {
                    var m = 0f
                    val s = (k * per).toInt(); val e = minOf(a.peak.size, ((k + 1) * per).toInt() + 1)
                    for (b in s until e) if (a.peak[b] > m) m = a.peak[b]
                    bars[k] = m
                }
            }
            val p = if (d.durationSec > 0) (d.positionSec / d.durationSec).toFloat() else 0f
            val cy = r.centerY(); val half = r.height() * 0.45f
            stroke.strokeWidth = 2f
            for (k in bars.indices) {
                val x = r.left + k * 2f
                val played = (k.toFloat() / bars.size) < p
                stroke.color = if (played) blend(deckColor(i), bg, 0.6f) else deckColor(i)
                c.drawLine(x, cy - bars[k] * half, x, cy + bars[k] * half, stroke)
            }
            fill.color = Color.WHITE
            val px = r.left + r.width() * p
            c.drawRect(px - 1.5f, r.top, px + 1.5f, r.bottom, fill)
            for (h in 0..3) if (!d.hotCues[h].isNaN() && d.pcm != null) {
                fill.color = hotCueColors[h]
                val hx = r.left + r.width() * (d.hotCues[h] / d.pcm!!.frames).toFloat()
                c.drawRect(hx - 1f, r.top, hx + 1f, r.top + r.height() * 0.35f, fill)
            }
        }
    }

    /** Title, artist, time, tempo and key of a deck. Tap for the deck menu. */
    inner class Info(r: RectF, private val i: Int) : Ctl(r) {
        private val d: Deck get() = mixer.decks[i]
        override fun down(x: Float, y: Float) = host.deckMenu(i)
        override fun draw(c: Canvas) {
            fill.color = panel; c.drawRoundRect(r, radius, radius, fill)
            val pad = unit * 0.6f
            val gap = unit * 0.8f
            val line1 = r.top + r.height() * 0.42f
            val line2 = r.top + r.height() * 0.82f
            val loading = host.loadingText(i)
            // Right-hand side first (time left; BPM, key and tempo change) so the title and
            // artist can use all the room that's left.
            var right1 = 0f; var right2 = 0f
            if (d.loaded) {
                mono.textAlign = Paint.Align.RIGHT
                mono.textSize = r.height() * 0.32f
                val remain = (d.durationSec - d.positionSec).coerceAtLeast(0.0)
                val warn = remain < 30 && d.playing && (now() / 400) % 2 == 0L
                mono.color = if (warn) 0xFFFF5252.toInt() else Color.WHITE
                val time = "-" + fmtTime(remain)
                c.drawText(time, r.right - pad, line1, mono)
                right1 = mono.measureText(time)
                // "93.00 BPM · 8A  +0.0%", drawn right to left; the key in its key-wheel colour.
                mono.textSize = r.height() * 0.25f
                var x = r.right - pad
                fun part(s: String, color: Int) { mono.color = color; c.drawText(s, x, line2, mono); x -= mono.measureText(s) }
                part("  %+.1f%%".format(d.pitch * 100), deckColor(i))
                KeyDisplay.label(d)?.let { k -> part(k, KeyDisplay.labelColor(d, Color.WHITE)); part(" · ", dim) }
                part((if (d.bpm > 0) "%.2f".format(d.effectiveBpm) else "--") + " BPM", deckColor(i))
                right2 = r.right - pad - x
            }
            text.textAlign = Paint.Align.LEFT
            text.color = Color.WHITE; text.textSize = r.height() * 0.32f
            val title = when {
                loading != null -> loading
                d.loaded -> d.title
                mixer.decks[1 - i].loaded -> "$tapCap NEXT for a song that fits"
                else -> "$tapCap LOAD to pick a song"
            }
            c.drawText(ellipsize(title, r.width() - 2 * pad - right1 - (if (right1 > 0) gap else 0f)), r.left + pad, line1, text)
            text.color = dim; text.textSize = r.height() * 0.25f
            // Line 2: the song queued to load when this one ends (or downloading for it), else the artist.
            val up = host.upNext(i)
            if (up != null) {
                text.color = nextTeal
                val maxW = r.width() - 2 * pad - right2 - (if (right2 > 0) gap else 0f)
                var s = "Up next: $up"
                // "Grillz · downloading 42%": shorten the title, not the progress; if that leaves
                // too little of the title, keep just the percentage ("Up next: Gril… · 42%"). On
                // the narrowest strips (the top bar with MIX, a long BPM and key on the right) one
                // letter of the title will do, or none: "Up next · 42%".
                val cut = up.lastIndexOf(" · ")
                if (text.measureText(s) > maxW && cut > 0) {
                    val head = "Up next: " + up.substring(0, cut)
                    fun fit(tail: String, letters: Int = 4): String? {
                        val h = ellipsize(head, maxW - text.measureText(tail))
                        return if (h.length >= minOf(head.length, 10 + letters)) h + tail else null
                    }
                    val pct = up.substringAfterLast(' ').takeIf { it.endsWith("%") }
                    s = fit(up.substring(cut)) ?: pct?.let { fit(" · $it") ?: fit(" · $it", letters = 1) ?: "Up next · $it" } ?: s
                }
                c.drawText(ellipsize(s, maxW), r.left + pad, line2, text)
            } else if (d.loaded && loading == null) c.drawText(ellipsize(d.artist, r.width() - 2 * pad - right2 - gap), r.left + pad, line2, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    inner class Meter(r: RectF, private val level: () -> Float) : Ctl(r) {
        override fun draw(c: Canvas) {
            val segs = 14
            val h = r.height() / segs
            val lv = level()
            for (k in 0 until segs) {
                val t = (k + 1f) / segs
                val on = lv >= t * 0.95f
                fill.color = when {
                    k >= segs - 2 -> if (on) 0xFFFF1744.toInt() else 0xFF3A1515.toInt()
                    k >= segs - 5 -> if (on) 0xFFFFEA00.toInt() else 0xFF3A3515.toInt()
                    else -> if (on) 0xFF00E676.toInt() else 0xFF15301E.toInt()
                }
                val y = r.bottom - (k + 1) * h
                c.drawRect(r.left, y + h * 0.15f, r.right, y + h * 0.85f, fill)
            }
        }
    }

    // ------------------------------------------------------------------ NEXT

    private val nextTeal = 0xFF1DE9B6.toInt()

    /**
     * NEXT's plan: load onto deck [target] a song that fits after deck [after]'s (null = both
     * decks empty). [waiting]: [target] already holds a song that hasn't been played yet.
     */
    class NextPlan(val target: Int, val after: Int?, val waiting: Boolean = false)

    private val seenPcm = arrayOfNulls<Any>(2)
    private val played = BooleanArray(2)
    /** The deck that was last playing on its own, or -1. */
    private var lastLive = -1

    /** Notices new songs and which deck is live (called every frame). */
    private fun watchDecks() {
        for (k in 0..1) {
            val d = mixer.decks[k]
            if (d.pcm !== seenPcm[k]) { seenPcm[k] = d.pcm; played[k] = false }
            // Holding CUE to pre-listen isn't a start; pressing PLAY during the preview is. Neither is
            // playing it where only the headphones hear it (crossfader or volume all the way down).
            if (d.playing && !d.previewing && !played[k] && heard(k)) { played[k] = true; host.deckStarted(k) }
        }
        val a = live(0); val b = live(1)
        if (a != b) lastLive = if (a) 0 else 1
    }

    /** Deck [k] is really playing (holding CUE to pre-listen doesn't count). */
    private fun live(k: Int) = mixer.decks[k].let { it.playing && !it.previewing }

    /** Deck [k] can be heard in the room: its volume fader and the crossfader let it through. */
    private fun heard(k: Int): Boolean {
        val g = mixer.crossfaderGains().let { if (k == 0) it.first else it.second }
        return mixer.decks[k].volume > 0.01f && g > 0.01f
    }

    /** Real seconds left on deck [k] at its current tempo. */
    private fun secondsLeft(k: Int): Double {
        val d = mixer.decks[k]
        return (d.durationSec - d.positionSec).coerceAtLeast(0.0) / (1 + d.pitch)
    }

    /**
     * Which deck NEXT loads, and which song the suggestions follow. One deck playing: the other
     * deck, after the playing song. Both playing (mid-mix): the deck that ends first, after the
     * other. None playing: after the song that played last (else deck A's). Reads the decks
     * without the lock (like drawing does): a stale value only lasts one frame.
     */
    fun nextPlan(): NextPlan {
        val d = mixer.decks
        // During a MIX: replace the song being mixed out, with what fits after the incoming one.
        mixer.autoMix.let { if (it.state != AutoMix.State.IDLE) return NextPlan(it.from, it.to, waiting = false) }
        val a = live(0); val b = live(1)
        val (target, after) = when {
            a && b -> if (secondsLeft(0) <= secondsLeft(1)) 0 to 1 else 1 to 0
            a -> 1 to 0
            b -> 0 to 1
            d[0].loaded && d[1].loaded -> {
                val ref = when { lastLive >= 0 -> lastLive; played[1] && !played[0] -> 1; else -> 0 }
                1 - ref to ref
            }
            d[0].loaded -> 1 to 0
            d[1].loaded -> 0 to 1
            else -> return NextPlan(0, null)
        }
        return NextPlan(target, after, waiting = d[target].loaded && !played[target])
    }

    /**
     * NEXT blinks when the only playing song is near its end (a minute plus the time a MIX takes)
     * and no new song waits on the other deck, or is on its way there (loading, downloading, or
     * up next, e.g. picked during the MIX that just finished).
     */
    private fun nextDue(): Boolean {
        val d = mixer.decks
        if (live(0) == live(1)) return false
        val live = if (live(0)) 0 else 1
        val other = 1 - live
        if (d[other].loaded && !played[other]) return false
        if (host.loadingText(other) != null || host.upNext(other) != null) return false
        return secondsLeft(live) < 60 + mixer.autoMix.mixSeconds(live) && (now() / 600) % 2 == 0L
    }

    // ------------------------------------------------------------------ MIX

    /** The deck MIX would blend into, if a new song waits there (or both decks play). */
    private fun mixInto(): Int? {
        val to = mixer.autoMix.pick()?.second ?: return null
        val d = mixer.decks[to]
        return to.takeIf { d.loaded && (live(to) || !played[to]) }
    }

    private fun mixLabel(): String {
        val am = mixer.autoMix
        return when (am.state) {
            AutoMix.State.ARMED -> "IN ${am.beatsToStart()}"
            AutoMix.State.RUNNING -> when (am.kind) {
                AutoMix.Kind.BARS -> "BAR ${am.current()}/${am.runBars}"
                AutoMix.Kind.BEATS -> "BEAT ${am.current()}/${am.length.toInt()}"
                AutoMix.Kind.TIMED -> "FADE"
            }
            AutoMix.State.IDLE -> mixInto()?.let { "MIX ▸ ${deckName(it)}" } ?: "MIX"
        }
    }

    private fun mixColor(): Int {
        val am = mixer.autoMix
        if (am.state != AutoMix.State.IDLE) return deckColor(am.to)
        return mixInto()?.let { deckColor(it) } ?: dim
    }

    /** Filled while MIX blends; pulses when the playing song ends within the MIX time plus 15 s. */
    private fun mixLit(): Boolean {
        val am = mixer.autoMix
        if (am.state != AutoMix.State.IDLE) return true
        val to = mixInto() ?: return false
        val from = 1 - to
        return am.secondsLeft(from) < am.mixSeconds(from) + 15 && (now() / 600) % 2 == 0L
    }

    private fun mixProgress(): Float? =
        mixer.autoMix.takeIf { it.state == AutoMix.State.RUNNING }?.progress?.toFloat()

    /** Hold MIX: 4 → 8 → 16 → 32 → 4 bars. */
    private fun cycleMixLength() {
        val am = mixer.autoMix
        val bars = AutoMix.LENGTHS[(AutoMix.LENGTHS.indexOf(am.bars) + 1) % AutoMix.LENGTHS.size]
        act { autoMix.bars = bars }
        val k = am.pick()?.first?.takeIf { mixer.decks[it].bpm > 0 }
        say(if (k == null) "MIX length: $bars bars" else {
            val bpm = mixer.decks[k].effectiveBpm
            "MIX length: $bars bars (${(bars * 4 * 60 / bpm).roundToInt()} s at ${bpm.roundToInt()} BPM)"
        })
        host.settingsChanged()
    }

    /**
     * MIX tapped (or M pressed on a computer). With no new song waiting (MIX is grey: the other
     * deck's song has played already, or it's still on its way), it says so instead of blending
     * back into the song that just played.
     */
    fun mixTapped() {
        val am = mixer.autoMix
        val to = am.pick()?.second
        if (am.state != AutoMix.State.IDLE || to == null || mixInto() != null) { act { mixPressed() }; return }
        val coming = host.loadingText(to) != null || host.upNext(to) != null
        say(if (coming) "Wait for the next song to load" else "Load the next song first: $tap NEXT", to)
    }

    /** The last MIX message shown on the notice line. */
    private var mixSeen = mixer.autoMix.messageSeq

    /**
     * Shows MIX's newest message (the engine posts them from the audio thread). When a new song is
     * on the deck MIX stopped, or on its way there (up next, or loading), its finish message leaves
     * out "tap NEXT"; the host's "Up next is ready" follows once that song has loaded. An error
     * on the notice line (a download that failed) stays until it's read: MIX's message waits.
     */
    private fun watchMix() {
        val am = mixer.autoMix
        val m = am.lastMessage ?: return
        if (m.seq == mixSeen) return
        notice?.let { if (it.error && now() < it.until) return }
        mixSeen = m.seq
        // watchDecks ran first, so a song just loaded there (Stop A and load) isn't played yet.
        val coming = host.upNext(am.from) != null || host.loadingText(am.from) != null ||
            (mixer.decks[am.from].loaded && !played[am.from])
        val text = if (coming) m.withUpNext else m.text
        // MIX's messages are written for a phone; only these fixed phrases change (never a song title).
        say(if (!computer) text else text.replace("tap MIX", "click MIX").replace("tap NEXT", "click NEXT")
            .replace("touch the crossfader", "move the crossfader"), m.deck)
    }

    // ------------------------------------------------------------------ ▶ and SYNC lights

    /**
     * ▶ is lit while the deck plays. It blinks fast while it waits to start on the other deck's
     * beat, and slowly when a new song is ready and nothing plays (press me to start).
     */
    private fun playLit(i: Int): Boolean {
        val d = mixer.decks[i]
        return when {
            d.pendingStart -> (now() / 150) % 2 == 0L
            d.playing -> true
            else -> d.loaded && !played[i] && !live(0) && !live(1) && (now() / 600) % 2 == 0L
        }
    }

    internal enum class SyncState { NO, IN_TIME, DRIFTED }

    /**
     * SYNC's light: [SyncState.IN_TIME] when the tempos match (or are half / double) and, when
     * both decks play at the same tempo, their beats are within 3% of a beat of each other;
     * [SyncState.DRIFTED] (it blinks) when the tempos match but the beats have drifted apart.
     */
    internal fun syncState(i: Int): SyncState {
        val d = mixer.decks[i]; val o = mixer.decks[1 - i]
        if (d.bpm <= 0 || o.bpm <= 0) return SyncState.NO
        val mine = d.effectiveBpm; val theirs = o.effectiveBpm
        val tempoMatch = abs(mine - theirs) < 0.02 || abs(mine * 2 - theirs) < 0.02 || abs(mine / 2 - theirs) < 0.02
        if (!tempoMatch) return SyncState.NO
        if (!(d.playing && o.playing) || abs(mine / theirs - 1) > 0.01) return SyncState.IN_TIME
        val diff = abs(d.beatPhase() - o.beatPhase()).let { minOf(it, 1 - it) }
        return if (diff <= 0.03) SyncState.IN_TIME else SyncState.DRIFTED
    }

    // ------------------------------------------------------------------ layout

    /** Lays the console out for a [w] × [h] pixel area. */
    fun layout(w: Int, h: Int) {
        if (w == width && h == height && ctls.isNotEmpty()) return
        width = w; height = h
        active.clear()
        ctls.clear()
        val W = w.toFloat(); val H = h.toFloat()
        unit = H / 52f
        radius = unit * 0.5f
        val pad = unit * 0.5f
        val topH = H * 0.095f
        val ovH = H * 0.05f
        val laneH = H * 0.095f

        // Top bar: [LOAD A][info A]  [REC][meter][NEXT][MIX][?][☰]  [info B][LOAD B]
        val loadW = W * 0.065f
        val centerW = W * 0.30f
        val infoW = (W - centerW - 2 * loadW - 6 * pad) / 2
        var x = pad
        ctls += Btn(RectF(x, pad, x + loadW, topH), { "LOAD" }, colA) { host.openLibrary(0) }.h("Pick a song for deck A").k("load0")
        x += loadW + pad
        ctls += Info(RectF(x, pad, x + infoW, topH), 0).h("Song, time left, BPM, key · $tap for BPM fixes").k("info0")
        x += infoW + pad
        // REC (1.4 parts) | meter (0.4) | NEXT (1.5) | MIX (1.5) | ? (0.7) | ☰ (0.9)
        val cw = (centerW - 5 * pad) / 6.4f
        var cx = x
        // REC: tap to start. While recording a tap only says how to stop: hold it (so a stray tap
        // can't end the set). It says HOLD while held.
        lateinit var rec: Btn
        rec = Btn(RectF(cx, pad, cx + cw * 1.4f, topH), {
            host.savingText() ?: if (host.recording()) (if (rec.held) "HOLD" else fmtTime(host.recordSeconds())) else "REC"
        }, 0xFFFF1744.toInt(), lit = { host.recording() }, tapOnUp = true,
            onLong = { if (host.recording()) host.stopRecord() else host.toggleRecord() }) {
            if (host.recording()) say(Menus.recordingHint(host.recordSeconds(), host.shortcut("rec"))) else host.toggleRecord()
        }
        ctls += rec.h("Record your mix").k("rec")
        cx += cw * 1.4f + pad
        ctls += Meter(RectF(cx, pad, cx + cw * 0.4f, topH)) { mixer.masterLevel }
        cx += cw * 0.4f + pad
        ctls += Btn(RectF(cx, pad, cx + cw * 1.5f, topH), { "NEXT" }, nextTeal, lit = { nextDue() },
            colorOf = { nextPlan().let { if (it.after == null) nextTeal else deckColor(it.target) } }) {
            host.suggestNext(nextPlan())
        }.h("Suggest the next song").k("next")
        cx += cw * 1.5f + pad
        // MIX: tap to blend into the waiting song (tap again to take over); hold to change its length.
        ctls += Btn(RectF(cx, pad, cx + cw * 1.5f, topH), { mixLabel() }, dim, lit = { mixLit() },
            colorOf = { mixColor() }, tapOnUp = true, progress = { mixProgress() },
            onLong = { if (mixer.autoMix.state == AutoMix.State.IDLE) cycleMixLength() else act { mixPressed() } }) {
            mixTapped()
        }.h("Blend into the waiting song").k("mix")
        cx += cw * 1.5f + pad
        ctls += Btn(RectF(cx, pad, cx + cw * 0.7f, topH), { "?" }, 0xFFFFEB3B.toInt(), lit = { helpMode }) { helpMode = !helpMode }
            .h("Labels on / off").k("help")
        cx += cw * 0.7f + pad
        ctls += Btn(RectF(cx, pad, x + centerW, topH), { "☰" }, dim, badge = { host.updateReady() }) { host.mainMenu() }
            .h("Menu: mixes, settings, help").k("menu")
        x += centerW + pad
        ctls += Info(RectF(x, pad, x + infoW, topH), 1).h("Song, time left, BPM, key · $tap for BPM fixes").k("info1")
        x += infoW + pad
        ctls += Btn(RectF(x, pad, W - pad, topH), { "LOAD" }, colB) { host.openLibrary(1) }.h("Pick a song for deck B").k("load1")
        // The help key's banner goes between the two song strips, where it covers no label.
        keyBanner.set(W * 0.35f, topH + pad * 0.25f, W * 0.65f, topH + pad * 1.75f + ovH)

        var y = topH + pad
        ctls += Overview(RectF(pad, y, W / 2 - pad / 2, y + ovH), 0).h("Whole song A · $tap to jump there").k("ov0")
        ctls += Overview(RectF(W / 2 + pad / 2, y, W - pad, y + ovH), 1).h("Whole song B · $tap to jump there").k("ov1")
        y += ovH + pad
        ctls += Lane(RectF(pad, y, W - pad, y + laneH), 0).h("Deck A close-up · beat lines on top · drag to scratch").k("lane0")
        y += laneH + pad * 0.5f
        ctls += Lane(RectF(pad, y, W - pad, y + laneH), 1).h("Deck B close-up · line the beats up with A").k("lane1")
        y += laneH + pad
        // The Start card covers the song overviews and close-ups while both decks are empty.
        startArea.set(pad, topH + pad, W - pad, y - pad)
        layoutStart()

        val mainTop = y
        val mainBottom = H - pad
        val deckW = W * 0.355f
        layoutDeck(0, RectF(pad, mainTop, pad + deckW, mainBottom), mirrored = false)
        layoutDeck(1, RectF(W - pad - deckW, mainTop, W - pad, mainBottom), mirrored = true)
        layoutMixer(RectF(pad * 2 + deckW, mainTop, W - pad * 2 - deckW, mainBottom))
    }

    private fun layoutDeck(i: Int, r: RectF, mirrored: Boolean) {
        val col = deckColor(i)
        val pad = unit * 0.45f
        val pitchW = r.width() * 0.11f
        val jogW = (r.width() - pitchW) * 0.53f
        val btnW = r.width() - pitchW - jogW
        // Mirrored for deck B so the buttons sit next to the mixer.
        val pitchR: RectF; val jogR: RectF; val btnR: RectF
        if (!mirrored) {
            pitchR = RectF(r.left, r.top, r.left + pitchW, r.bottom)
            jogR = RectF(pitchR.right + pad, r.top, pitchR.right + jogW, r.bottom)
            btnR = RectF(jogR.right + pad, r.top, r.right, r.bottom)
        } else {
            pitchR = RectF(r.right - pitchW, r.top, r.right, r.bottom)
            jogR = RectF(pitchR.left - jogW, r.top, pitchR.left - pad, r.bottom)
            btnR = RectF(r.left, r.top, jogR.left - pad, r.bottom)
        }
        val d = { mixer.decks[i] }

        // Pitch: range button on top, fader below (down = faster, like CDJs).
        val rangeH = unit * 3f
        ctls += Btn(RectF(pitchR.left, pitchR.top, pitchR.right, pitchR.top + rangeH),
            { "±${(d().pitchRange * 100).toInt()}" }, col, textScale = 0.8f) {
            act { val dk = decks[i]; dk.pitchRange = when (dk.pitchRange) { 0.08 -> 0.16; 0.16 -> 0.5; else -> 0.08 } }
        }.h("Tempo range").k("range$i")
        ctls += Fader(RectF(pitchR.left, pitchR.top + rangeH + pad, pitchR.right, pitchR.bottom - unit * 1.6f), col,
            { (d().pitch / d().pitchRange).toFloat() }, { v -> mixer.decks[i].pitch = v * mixer.decks[i].pitchRange },
            -1f, 1f, 0f, invert = true, caption = { "%+.1f%%".format(d().pitch * 100) }).h("Tempo (down = faster)").k("pitch$i")

        // Jog wheel above CUE / PLAY.
        val transportH = jogR.height() * 0.24f
        val jogArea = RectF(jogR.left, jogR.top, jogR.right, jogR.bottom - transportH - pad)
        val side = min(jogArea.width(), jogArea.height())
        ctls += Jog(RectF(jogArea.centerX() - side / 2, jogArea.centerY() - side / 2, jogArea.centerX() + side / 2, jogArea.centerY() + side / 2), i)
            .h("Jog wheel · middle: scratch · edge: speed up / slow down").k("jog$i")
        val ty = jogR.bottom - transportH
        val half = (jogR.width() - pad) / 2
        ctls += Btn(RectF(jogR.left, ty, jogR.left + half, jogR.bottom), { "CUE" }, 0xFFFFA000.toInt(),
            onUp = { act { decks[i].cueUp() } }) { act { decks[i].cueDown(quantize) } }.h("Cue: back to start point").k("cue$i")
        ctls += Btn(RectF(jogR.left + half + pad, ty, jogR.right, jogR.bottom), { if (d().playing) "❚❚" else "▶" }, 0xFF00E676.toInt(),
            lit = { playLit(i) }) { if (leadsToLoad(i)) host.openLibrary(i) else act { playPressed(i) } }.h("Play / pause").k("play$i")

        // Button grid: 6 rows.
        val rows = 6
        val rh = (btnR.height() - pad * (rows - 1)) / rows
        fun row(k: Int) = btnR.top + k * (rh + pad)
        fun cell(k: Int, c: Int, n: Int): RectF {
            val w = (btnR.width() - pad * (n - 1)) / n
            val x = btnR.left + c * (w + pad)
            return RectF(x, row(k), x + w, row(k) + rh)
        }
        ctls += Btn(cell(0, 0, 2), { "SYNC" }, col, lit = {
            when (syncState(i)) { SyncState.IN_TIME -> true; SyncState.DRIFTED -> (now() / 600) % 2 == 0L; else -> false }
        }) { act { sync(i) } }.h("Match tempo & beat · blinks: beats apart").k("sync$i")
        ctls += Btn(cell(0, 1, 2), { "VINYL" }, col, lit = { d().vinylMode }) { act { decks[i].vinylMode = !decks[i].vinylMode } }.h("Vinyl: jog middle scratches").k("vinyl$i")
        for (h in 0..3) {
            var wasSet = false
            ctls += Btn(cell(1 + h / 2, h % 2, 2), { if (d().hotCues[h].isNaN()) "${h + 1}" else "● ${h + 1}" }, hotCueColors[h],
                lit = { !d().hotCues[h].isNaN() },
                onLong = { if (wasSet) act { decks[i].clearHotCue(h) } }) {
                wasSet = !d().hotCues[h].isNaN()
                act { decks[i].hotCue(h, quantize) }
            }.h("Hot cue · hold to clear").k("hc${i}_$h")
        }
        val loopCol = 0xFF32CD32.toInt()
        listOf(1.0, 2.0, 4.0, 8.0).forEachIndexed { k, beats ->
            ctls += Btn(cell(3, k, 4), { beats.toInt().toString() }, loopCol,
                lit = { d().loopOn && d().loopBeats == beats }) { act { decks[i].autoLoop(beats, quantize) } }.h("Loop ${beats.toInt()} beat${if (beats > 1) "s" else ""}").k("loop${i}_${beats.toInt()}")
        }
        ctls += Btn(cell(4, 0, 4), { "½" }, loopCol) { act { decks[i].loopResize(0.5) } }.h("Halve loop").k("half$i")
        ctls += Btn(cell(4, 1, 4), { "×2" }, loopCol) { act { decks[i].loopResize(2.0) } }.h("Double loop").k("double$i")
        ctls += Btn(cell(4, 2, 4), { "IN" }, loopCol, textScale = 0.85f) { act { decks[i].loopInHere(quantize) } }.h("Loop start")
        ctls += Btn(cell(4, 3, 4), { "OUT" }, loopCol, lit = { d().loopOn }, textScale = 0.85f) {
            act {
                val dk = decks[i]
                if (dk.loopOn || (dk.loopIn >= 0 && dk.loopOut > dk.loopIn)) dk.loopToggle() else dk.loopOutHere(quantize)
            }
        }.h("Loop end / exit")
        val fxCol = 0xFFB388FF.toInt()
        val fxType = cell(5, 0, 3)
        ctls += Btn(RectF(fxType.left, fxType.top, fxType.right + (fxType.width() + pad) * 0.35f, fxType.bottom),
            { d().fx.type.label }, fxCol, textScale = 0.7f) {
            act { val fx = decks[i].fx; fx.type = FxType.values()[(fx.type.ordinal + 1) % FxType.values().size] }
        }.h("Choose effect").k("fxtype$i")
        val fxOn = cell(5, 1, 3)
        ctls += Btn(RectF(fxOn.left + (fxOn.width() + pad) * 0.35f, fxOn.top, fxOn.right + (fxOn.width() + pad) * 0.35f, fxOn.bottom),
            { "FX" }, fxCol, lit = { d().fx.on }) { act { decks[i].fx.on = !decks[i].fx.on } }.h("Effect on/off").k("fx$i")
        val fxAmt = cell(5, 2, 3)
        ctls += Knob(RectF(fxAmt.left + (fxAmt.width() + pad) * 0.35f, fxAmt.top, fxAmt.right, fxAmt.bottom), "AMT", fxCol,
            { d().fx.amount }, { v -> mixer.decks[i].fx.amount = v }, 0f, 1f, 0.5f).h("Amount").k("amt$i")
    }

    private fun layoutMixer(r: RectF) {
        val pad = unit * 0.4f
        val topH = r.height() * 0.70f
        val chW = (r.width() - pad) / 2
        for (i in 0..1) {
            val col = deckColor(i)
            val left = if (i == 0) r.left else r.left + chW + pad
            val knobW = chW * 0.42f
            val faderW = chW * 0.38f
            val meterW = chW * 0.12f
            // A: knobs | fader | meter      B: meter | fader | knobs
            val knobX: Float; val faderX: Float; val meterX: Float
            if (i == 0) { knobX = left; faderX = left + knobW + pad; meterX = faderX + faderW + pad }
            else { meterX = left + chW - knobW - faderW - meterW - 2 * pad; faderX = meterX + meterW + pad; knobX = faderX + faderW + pad }
            val d = { mixer.decks[i] }
            val kh = topH / 5
            val knobs = listOf<Triple<String, () -> Float, (Float) -> Unit>>(
                Triple("TRIM", { (d().trimDb / 12).toFloat() }, { v -> mixer.decks[i].trimDb = v * 12.0 }),
                Triple("HI", { d().eqHigh }, { v -> mixer.decks[i].eqHigh = v }),
                Triple("MID", { d().eqMid }, { v -> mixer.decks[i].eqMid = v }),
                Triple("LOW", { d().eqLow }, { v -> mixer.decks[i].eqLow = v }),
                Triple("FILTER", { d().filter }, { v -> mixer.decks[i].filter = v }),
            )
            knobs.forEachIndexed { k, (label, get, set) ->
                ctls += Knob(RectF(knobX, r.top + k * kh, knobX + knobW, r.top + (k + 1) * kh), label,
                    if (label == "FILTER") 0xFFB388FF.toInt() else col, get, set).h(when (label) {
                        "TRIM" -> "Loudness"; "HI" -> "Treble"; "MID" -> "Mids"; "LOW" -> "Bass"; else -> "Filter sweep"
                    }).k(label.lowercase() + i)
            }
            val cueH = unit * 3f
            ctls += Btn(RectF(faderX, r.top, faderX + faderW, r.top + cueH), { "🎧" }, 0xFFFFA000.toInt(),
                lit = { d().headphone }, textScale = 0.9f) {
                act { decks[i].headphone = !decks[i].headphone }
                // Pre-listening needs split cue: offer to turn it on.
                if (mixer.decks[i].headphone && !mixer.splitCue) host.splitCueOff()
            }.h("Pre-listen").k("phones$i")
            ctls += Fader(RectF(faderX, r.top + cueH + pad, faderX + faderW, r.top + topH - pad), col,
                { d().volume }, { v -> mixer.decks[i].volume = v }, 0f, 1f, 1f).h("Volume ${if (i == 0) "A" else "B"}").k("vol$i")
            ctls += Meter(RectF(meterX, r.top + cueH + pad, meterX + meterW, r.top + topH - pad)) { mixer.decks[i].level * mixer.decks[i].volume }
        }
        // Sampler pads.
        val padY = r.top + topH + pad
        val padH = (r.bottom - padY) * 0.42f
        val pw = (r.width() - pad * 3) / 4
        for (k in 0..3) {
            ctls += Btn(RectF(r.left + k * (pw + pad), padY, r.left + k * (pw + pad) + pw, padY + padH),
                { mixer.sampler.names[k] }, 0xFFFF4081.toInt(), lit = { mixer.sampler.isPlaying(k) }, textScale = 0.62f) {
                act { if (sampler.isPlaying(k)) sampler.stop(k) else sampler.trigger(k) }
            }.h("Sound effect").k("pad$k")
        }
        // Crossfader.
        ctls += Fader(RectF(r.left, padY + padH + pad * 3, r.right, r.bottom), Color.WHITE,
            { mixer.crossfader }, { v -> mixer.crossfader = v }, -1f, 1f, 0f, horizontal = true).h("Crossfader: A ◀ blend ▶ B").k("xfader")
    }

    // ------------------------------------------------------------------ empty decks and the Start card

    /** Deck [i] is empty and nothing is on its way: touching it (▶, the jog's middle, its close-up) opens the library. */
    private fun leadsToLoad(i: Int) = !mixer.decks[i].loaded && tutorial == null && host.loadingText(i) == null

    private val startArea = RectF()
    /** The Start card's three buttons, left to right. */
    internal val startBtns = arrayOf(RectF(), RectF(), RectF())

    /** Both decks empty and nothing loading, no tutorial or labels: the Start card shows the ways to start. */
    fun showStart() = tutorial == null && !helpMode && mixer.decks.none { it.loaded } &&
        host.loadingText(0) == null && host.loadingText(1) == null

    private class StartChoice(val title: String, val sub: String, val color: Int, val filled: Boolean, val go: () -> Unit)

    private val tutorialSub = "Nelly · Air Force Ones → Grillz · about 5 min"

    /** A newcomer is invited to learn first; a returning user to load a song. */
    private fun startChoices(): List<StartChoice> {
        val learn = { host.startTutorial() }
        val load = { host.openLibrary(0) }
        val labels = StartChoice("What does each button do?", "Puts a label on every control", 0xFFFFEB3B.toInt(), false) { helpMode = true }
        return if (newcomer) listOf(
            StartChoice("▶  Learn with a guided mix", tutorialSub, 0xFF00E676.toInt(), true, learn),
            StartChoice("Load my first song", "YouTube search, a Spotify link or your own files", colA, false, load),
            labels,
        ) else listOf(
            StartChoice("Load a song", "My songs, YouTube, Spotify or your files", colA, true, load),
            StartChoice("▶  Learn with a guided mix", tutorialSub, 0xFF00E676.toInt(), false, learn),
            labels,
        )
    }

    private fun startTitle() = if (newcomer) "Welcome to MixDeck · how do you want to start?" else "Both decks are empty · pick a song to start"

    private fun layoutStart() {
        val a = startArea
        val titleH = a.height() * 0.22f
        val gap = unit * 1.2f
        val bw = min((a.width() - 4 * gap) / 3, unit * 34f)
        val bh = a.height() - titleH - gap * 1.4f
        val x0 = a.centerX() - (3 * bw + 2 * gap) / 2
        val top = a.top + titleH + gap * 0.4f
        startBtns.forEachIndexed { k, r -> r.set(x0 + k * (bw + gap), top, x0 + k * (bw + gap) + bw, top + bh) }
    }

    /** A touch on the Start card: its buttons act; anywhere else on the card does nothing. */
    private fun startTap(x: Float, y: Float): Boolean {
        if (!showStart()) return false
        val k = startBtns.indexOfFirst { it.contains(x, y) }
        if (k >= 0) { startChoices()[k].go(); return true }
        return startArea.contains(x, y)
    }

    private fun drawStart(c: Canvas) {
        val a = startArea
        fill.color = bg; c.drawRoundRect(a, radius, radius, fill)
        stroke.color = btnBg; stroke.strokeWidth = unit * 0.12f; c.drawRoundRect(a, radius, radius, stroke)
        val titleH = a.height() * 0.22f
        val title = startTitle()
        // A notice (e.g. "MixDeck 1.130 is downloaded") sits where the title is: it stands in for the
        // title while it shows, and the title fades back as the notice fades out.
        val shown = 1f - noticeAlpha()
        if (shown > 0f) {
            text.color = (((0xFF * shown).toInt() shl 24) or 0xFFFFFF)
            text.textSize = titleH * 0.62f; fitText(title, a.width() * 0.94f)
            c.drawText(title, a.centerX(), a.top + titleH * 0.82f, text)
        }
        startChoices().forEachIndexed { k, ch ->
            val r = startBtns[k]
            val bh = r.height(); val bw = r.width()
            fill.color = if (ch.filled) ch.color else btnBg; c.drawRoundRect(r, radius * 1.5f, radius * 1.5f, fill)
            if (!ch.filled) {
                stroke.color = ch.color; stroke.strokeWidth = unit * 0.15f
                c.drawRoundRect(inset(r, unit * 0.08f), radius * 1.5f, radius * 1.5f, stroke)
            }
            text.color = if (ch.filled) Color.BLACK else Color.WHITE
            text.textSize = min(bh * 0.30f, unit * 1.5f); fitText(ch.title, bw * 0.9f)
            c.drawText(ch.title, r.centerX(), r.centerY() - bh * 0.02f, text)
            text.color = if (ch.filled) blend(ch.color, Color.BLACK, 0.75f) else dim
            text.textSize = min(bh * 0.20f, unit * 1.0f); fitText(ch.sub, bw * 0.92f)
            c.drawText(ch.sub, r.centerX(), r.centerY() + bh * 0.28f, text)
        }
    }

    // ------------------------------------------------------------------ touch & draw

    /** A finger (or the mouse) went down. [id] tells fingers apart for multi-touch. */
    fun pointerDown(id: Int, x: Float, y: Float, first: Boolean) {
        if (helpMode) {
            // Any touch closes the labels; the banner also opens the full guide.
            if (first) { helpMode = false; if (keyBanner.contains(x, y)) host.openGuide() }
            return
        }
        if (startTap(x, y)) return
        // A tap on the notice dismisses it: the strips under it can't be seen, so it never seeks them.
        if (tutorial == null && noticeAlpha() > 0f && keyBanner.contains(x, y)) { notice = null; return }
        tutorial?.let { t ->
            when {
                tutExit.contains(x, y) -> { tutorial = null; return }
                tutBack.contains(x, y) -> { if (t.index > 0) { t.index--; t.doneAt = 0 }; return }
                tutNext.contains(x, y) -> { advanceTutorial(t); return }
                tutRetry.contains(x, y) -> { host.retryTutorialSongs(); return }
            }
            // Only the highlighted controls respond during the tutorial.
            if (tutHoles.none { it.contains(x, y) }) return
        }
        val hit = ctls.lastOrNull { grow(it.r).contains(x, y) }
        if (hit != null) { active[id] = hit; hit.down(x, y) }
    }

    fun pointerMove(id: Int, x: Float, y: Float) { active[id]?.move(x, y) }

    /** Mouse wheel: turns knobs, moves faders, nudges the jog wheel. */
    fun wheel(x: Float, y: Float, notches: Float) {
        if (helpMode || tutorial?.let { tutHoles.none { h -> h.contains(x, y) } } == true) return
        if (showStart() && startArea.contains(x, y)) return
        ctls.lastOrNull { grow(it.r).contains(x, y) }?.wheel(notches)
    }

    fun pointerUp(id: Int) { active.remove(id)?.up() }

    fun cancelPointers() {
        for (c in active.values) c.cancel()
        active.clear()
    }

    /**
     * Whether control [key] works now: always, except during the tutorial, when only the controls
     * it highlights do (a computer's keys follow the same rule as clicks).
     */
    fun allows(key: String): Boolean = tutorial?.let { key in tutorialKeys(it) } ?: true

    /**
     * The controls tutorial [t] highlights now. Step 1 waits for its songs; if they couldn't be
     * fetched, the LOAD buttons light up too, so you can pick them yourself.
     */
    private fun tutorialKeys(t: Tutorial): List<String> {
        val gettingSongs = t.index == 0 && !(mixer.decks[0].loaded && mixer.decks[1].loaded)
        return if (gettingSongs && t.failed) t.step.keys + listOf("load0", "load1") else t.step.keys
    }

    /** Where the control the tutorial calls [key] is (for tests). */
    internal fun rectOf(key: String): RectF? = ctls.firstOrNull { it.key == key }?.r

    private val tmp = RectF()
    private fun grow(r: RectF): RectF { tmp.set(r); tmp.inset(-unit * 0.2f, -unit * 0.2f); return tmp }

    /** Draws one frame. Call it continuously (every display refresh) while the console is visible. */
    fun draw(c: Canvas) {
        watchDecks()
        watchMix()
        c.drawColor(bg)
        for (ctl in ctls) ctl.draw(c)
        if (showStart()) drawStart(c)
        if (!helpMode && tutorial == null) drawNotice(c)
        if (helpMode) drawKey(c)
        tutorial?.let { drawTutorial(c, it) }
    }

    /** How visible the notice is: 1 while it shows, fading to 0 over its last 400 ms (0 without one). */
    private fun noticeAlpha(): Float {
        val n = notice ?: return 0f
        val left = n.until - now()
        return if (left <= 0) 0f else (left / 400f).coerceAtMost(1f)
    }

    /** The notice line, between the two song strips. A tap on it dismisses it (see [pointerDown]). */
    private fun drawNotice(c: Canvas) {
        val n = notice ?: return
        val alpha = noticeAlpha()
        if (alpha <= 0f) return
        fun a(color: Int) = (((color ushr 24) * alpha).toInt() shl 24) or (color and 0xFFFFFF)
        val r = keyBanner
        fill.color = a(0xF21C1C22.toInt()); c.drawRoundRect(r, radius, radius, fill)
        stroke.color = a(when { n.error -> 0xFFFF5252.toInt(); n.deck < 0 -> dim; else -> deckColor(n.deck) })
        stroke.strokeWidth = unit * 0.12f
        c.drawRoundRect(inset(r, unit * 0.06f), radius, radius, stroke)
        text.color = a(Color.WHITE)
        val maxW = r.width() * 0.92f
        text.textSize = r.height() * 0.32f
        fitText(n.text, maxW)
        if (text.textSize >= unit * 0.8f) {
            c.drawText(n.text, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            return
        }
        // Too long for one line: two smaller lines.
        text.textSize = r.height() * 0.26f
        var lines = wrapFixed(n.text, maxW)
        if (lines.size > 2) lines = listOf(lines[0], ellipsize(lines.drop(1).joinToString(" "), maxW))
        val widest = lines.maxOf { text.measureText(it) }
        if (widest > maxW) text.textSize *= maxW / widest
        val lh = text.textSize * 1.15f
        val mid = r.centerY() - (text.descent() + text.ascent()) / 2
        lines.forEachIndexed { k, l -> c.drawText(l, r.centerX(), mid + (k - (lines.size - 1) / 2f) * lh, text) }
    }

    /** The help key: dims the console and labels each control with what it does. */
    private fun drawKey(c: Canvas) {
        c.drawColor(0xC8000000.toInt())
        val bubble = RectF()
        for (ctl in ctls) {
            val h = ctl.help ?: continue
            val r = ctl.r
            // On a computer, the key that does the same sits in a key cap under the label.
            val key = ctl.key?.let { host.shortcut(it) }
            bubble.set(r); bubble.inset(unit * 0.15f, unit * 0.15f)
            val capH = if (key != null) min(unit * 1.5f, bubble.height() * 0.3f) else 0f
            fill.color = 0xF2FFF59D.toInt()
            stroke.color = 0xFF000000.toInt(); stroke.strokeWidth = 1f
            text.color = Color.BLACK
            // Fit the label in one, two or three lines.
            val maxSize = min(unit * 1.25f, (bubble.height() - capH) * 0.42f)
            val lines = wrap(h, bubble.width() * 0.9f, (bubble.height() - capH) * 0.9f, maxSize)
            val lh = text.textSize * 1.15f
            val boxH = lh * lines.size + text.textSize * 0.5f
            val widest = lines.maxOf { text.measureText(it) } + text.textSize
            val top = r.centerY() - (boxH + capH) / 2
            val box = RectF(r.centerX() - widest / 2, top, r.centerX() + widest / 2, top + boxH)
            c.drawRoundRect(box, radius, radius, fill)
            var y = box.top + text.textSize * 0.25f - text.ascent()
            for (line in lines) { c.drawText(line, box.centerX(), y, text); y += lh }
            if (key != null) {
                mono.textAlign = Paint.Align.CENTER; mono.textSize = capH * 0.62f
                val w = mono.measureText(key) + capH * 0.7f
                val cap = RectF(r.centerX() - w / 2, box.bottom + capH * 0.08f, r.centerX() + w / 2, box.bottom + capH)
                fill.color = Color.BLACK; c.drawRoundRect(cap, radius * 0.6f, radius * 0.6f, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f; c.drawRoundRect(cap, radius * 0.6f, radius * 0.6f, stroke)
                mono.color = Color.WHITE; c.drawText(key, cap.centerX(), cap.centerY() - (mono.descent() + mono.ascent()) / 2, mono)
                mono.textAlign = Paint.Align.RIGHT
            }
        }
        // Banner between the two song strips: tap it for the full guide.
        val banner = keyBanner
        fill.color = 0xFFFFEB3B.toInt(); c.drawRoundRect(banner, radius, radius, fill)
        val l1 = if (computer) "BUTTON LABELS · click anywhere or press Esc to close" else "BUTTON LABELS · tap anywhere to close"
        val l2 = if (computer) "Click here for the full guide and shortcuts ›" else "Tap here for the full guide ›"
        text.color = Color.BLACK; text.textSize = banner.height() * 0.36f
        fitText(l1, banner.width() * 0.92f)
        c.drawText(l1, banner.centerX(), banner.centerY() - text.textSize * 0.15f, text)
        text.textSize = banner.height() * 0.25f
        fitText(l2, banner.width() * 0.92f)
        c.drawText(l2, banner.centerX(), banner.centerY() + text.textSize * 1.2f, text)
    }

    private fun advanceTutorial(t: Tutorial) {
        t.doneAt = 0
        if (t.index < t.steps.size - 1) t.index++ else tutorial = null
    }

    /** Dims everything except the controls this step is about, and shows the instructions. */
    private fun drawTutorial(c: Canvas, t: Tutorial) {
        val step = t.step
        val met = step.done?.invoke(mixer) == true
        if (met && t.doneAt == 0L) t.doneAt = now()
        if (t.doneAt > 0 && now() - t.doneAt > 1200 && t.index < t.steps.size - 1) { advanceTutorial(t); return }

        // Step 1 waits for its songs: no Next until both are on the decks. If they couldn't be
        // fetched, "Try again", and the LOAD buttons light up so you can pick them yourself.
        val gettingSongs = t.index == 0 && !(mixer.decks[0].loaded && mixer.decks[1].loaded)
        val keys = tutorialKeys(t)
        // Both songs arrived after all (e.g. picked with LOAD): the error is old news.
        if (t.index == 0 && !gettingSongs && t.failed) { t.failed = false; t.status = null }

        // Holes around the highlighted controls.
        tutHoles.clear()
        for (ctl in ctls) if (ctl.key != null && ctl.key in keys) {
            tutHoles += RectF(ctl.r).apply { inset(-unit * 0.35f, -unit * 0.35f) }
        }
        val scrim = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat())
            for (h in tutHoles) addRoundRect(h, radius * 1.5f, radius * 1.5f)
        }
        fill.color = 0xB8000000.toInt()
        c.drawPath(scrim, fill)
        val pulse = (0.5f + 0.5f * sin(now() / 160.0).toFloat())
        stroke.color = if (t.doneAt > 0) 0xFF00E676.toInt() else 0xFFFFEB3B.toInt()
        stroke.strokeWidth = unit * (0.25f + 0.25f * pulse)
        for (h in tutHoles) c.drawRoundRect(h, radius * 1.5f, radius * 1.5f, stroke)
        // Notices stay hidden while the steps teach (a load's "Press ▶" would get ahead of them), but
        // the last step's REC and MIX speak only through them, and an error must be seen.
        if (t.index == t.steps.size - 1 || notice?.error == true) drawNotice(c)

        // The instruction card, placed where it doesn't cover the highlighted controls.
        val cardW = width * 0.44f
        val pad = unit * 1.1f
        val bodySize = unit * 1.05f
        text.textAlign = Paint.Align.LEFT
        text.textSize = bodySize
        val lines = wrapFixed(if (computer) Guide.clickify(step.text) else step.text, cardW - 2 * pad)
        val status = t.status
        text.textSize = bodySize * 0.95f
        val statusLines = if (status != null) wrapFixed(status, cardW - 2 * pad) else emptyList()
        val btnH = unit * 2.6f
        val cardH = pad + unit * 1.3f + unit * 1.6f + lines.size * bodySize * 1.3f +
            statusLines.size * bodySize * 1.25f + unit * 1.8f + btnH + pad
        val union = RectF().apply { tutHoles.forEach { union(it) } }
        val candidates = listOf(
            RectF((width - cardW) / 2, unit, (width + cardW) / 2, unit + cardH),
            RectF((width - cardW) / 2, (height - cardH) / 2, (width + cardW) / 2, (height + cardH) / 2),
            RectF((width - cardW) / 2, height - cardH - unit, (width + cardW) / 2, height - unit.toFloat()),
            RectF(unit, (height - cardH) / 2, unit + cardW, (height + cardH) / 2),
            RectF(width - cardW - unit, (height - cardH) / 2, width - unit.toFloat(), (height + cardH) / 2),
        )
        val card = candidates.firstOrNull { cand -> tutHoles.none { RectF.intersects(it, cand) } } ?: run {
            if (union.centerY() > height / 2) candidates[0] else candidates[2]
        }
        fill.color = 0xF51C1C22.toInt()
        c.drawRoundRect(card, radius * 2, radius * 2, fill)
        stroke.color = 0xFFFFEB3B.toInt(); stroke.strokeWidth = unit * 0.15f
        c.drawRoundRect(card, radius * 2, radius * 2, stroke)

        var y = card.top + pad + unit * 0.9f
        text.color = 0xFFFFEB3B.toInt(); text.textSize = unit * 0.95f
        c.drawText("TUTORIAL · STEP ${t.index + 1} OF ${t.steps.size}", card.left + pad, y, text)
        y += unit * 1.8f
        text.color = Color.WHITE; text.textSize = unit * 1.45f
        c.drawText(step.title, card.left + pad, y, text)
        y += unit * 0.6f
        text.textSize = bodySize; text.color = 0xFFE6E6EC.toInt()
        for (l in lines) { y += bodySize * 1.3f; c.drawText(l, card.left + pad, y, text) }
        if (statusLines.isNotEmpty()) {
            text.color = 0xFF80DEEA.toInt(); text.textSize = bodySize * 0.95f
            for (l in statusLines) { y += bodySize * 1.25f; c.drawText(l, card.left + pad, y, text) }
        }
        text.textAlign = Paint.Align.CENTER

        // Buttons: Exit · Back · Next/Skip.
        val by = card.bottom - pad - btnH
        val bw = (cardW - 2 * pad - 2 * unit) / 3
        tutExit.set(card.left + pad, by, card.left + pad + bw, by + btnH)
        tutBack.set(tutExit.right + unit, by, tutExit.right + unit + bw, by + btnH)
        tutNext.set(tutBack.right + unit, by, card.right - pad, by + btnH)
        fun button(r: RectF, label: String, bg: Int, fg: Int) {
            fill.color = bg; c.drawRoundRect(r, radius, radius, fill)
            text.color = fg; text.textSize = btnH * 0.4f; fitText(label, r.width() * 0.9f)
            c.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        button(tutExit, "Exit", btnBg, Color.WHITE)
        if (t.index > 0) button(tutBack, "◀ Back", btnBg, Color.WHITE) else tutBack.setEmpty()
        val last = t.index == t.steps.size - 1
        tutWait.setEmpty(); tutRetry.setEmpty()
        when {
            gettingSongs -> {
                tutWait.set(tutNext); tutNext.setEmpty()
                if (t.failed) { tutRetry.set(tutWait); button(tutRetry, "Try again", 0xFFFFEB3B.toInt(), Color.BLACK) }
                else button(tutWait, "Getting songs…", btnBg, dim)
            }
            t.doneAt > 0 && !last -> button(tutNext, "✓ Nice!", 0xFF00E676.toInt(), Color.BLACK)
            last -> button(tutNext, "Finish", 0xFFFFEB3B.toInt(), Color.BLACK)
            step.done == null -> button(tutNext, "Next ▶", 0xFFFFEB3B.toInt(), Color.BLACK)
            else -> button(tutNext, "Skip ▶", btnBg, 0xFFFFEB3B.toInt())
        }
    }

    /** Word-wraps at the current text size. */
    private fun wrapFixed(s: String, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (w in s.split(' ')) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (text.measureText(t) <= maxW || cur.isEmpty()) cur = t else { lines += cur; cur = w }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    private fun wrap(s: String, maxW: Float, maxH: Float, maxSize: Float): List<String> {
        var size = maxSize
        while (size > unit * 0.45f) {
            text.textSize = size
            val words = s.split(' ')
            val lines = ArrayList<String>()
            var cur = ""
            for (w in words) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (text.measureText(t) <= maxW || cur.isEmpty()) cur = t else { lines += cur; cur = w }
            }
            if (cur.isNotEmpty()) lines += cur
            val fits = lines.all { text.measureText(it) <= maxW } && lines.size * size * 1.15f + size * 0.5f <= maxH
            if (fits) return lines
            size *= 0.9f
        }
        return listOf(s)
    }

    private fun drawEmpty(c: Canvas, i: Int) {
        text.color = dim; text.textSize = unit * 1.2f
        val r = ctls.filterIsInstance<Lane>().getOrNull(i)?.r ?: return
        val empty = "Deck ${deckName(i)} is empty" + if (leadsToLoad(i)) " · $tap here to load a song" else ""
        c.drawText(host.loadingText(i) ?: empty, r.centerX(), r.centerY() + unit * 0.4f, text)
    }

    // ------------------------------------------------------------------ helpers

    /** Icons drawn as shapes, so they look the same with any font. Returns false if [s] isn't one. */
    private fun drawIcon(c: Canvas, s: String, r: RectF, color: Int): Boolean {
        val sz = min(r.width(), r.height()) * 0.32f
        val cx = r.centerX(); val cy = r.centerY()
        fill.color = color
        when (s) {
            "▶" -> c.drawPath(Path().apply { addPoly(cx - sz * 0.45f, cy - sz * 0.55f, cx + sz * 0.6f, cy, cx - sz * 0.45f, cy + sz * 0.55f) }, fill)
            "❚❚" -> {
                c.drawRect(cx - sz * 0.5f, cy - sz * 0.55f, cx - sz * 0.15f, cy + sz * 0.55f, fill)
                c.drawRect(cx + sz * 0.15f, cy - sz * 0.55f, cx + sz * 0.5f, cy + sz * 0.55f, fill)
            }
            "☰" -> for (k in -1..1) c.drawRect(cx - sz * 0.6f, cy + k * sz * 0.4f - sz * 0.08f, cx + sz * 0.6f, cy + k * sz * 0.4f + sz * 0.08f, fill)
            "🎧" -> {
                stroke.color = color; stroke.strokeWidth = sz * 0.16f
                val o = RectF(cx - sz * 0.6f, cy - sz * 0.6f, cx + sz * 0.6f, cy + sz * 0.6f)
                c.drawArc(o, 180f, 180f, false, stroke)
                c.drawRoundRect(RectF(cx - sz * 0.72f, cy - sz * 0.05f, cx - sz * 0.38f, cy + sz * 0.6f), sz * 0.1f, sz * 0.1f, fill)
                c.drawRoundRect(RectF(cx + sz * 0.38f, cy - sz * 0.05f, cx + sz * 0.72f, cy + sz * 0.6f), sz * 0.1f, sz * 0.1f, fill)
            }
            else -> return false
        }
        return true
    }

    private fun fitText(s: String, maxW: Float) {
        val w = text.measureText(s)
        if (w > maxW) text.textSize *= maxW / w
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 0 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)

    companion object {
        fun fmtTime(sec: Double): String {
            val s = sec.toInt().coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = ((((a shr sh) and 0xFF) * (1 - t)) + (((b shr sh) and 0xFF) * t)).toInt() and 0xFF
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun deckName(i: Int) = if (i == 0) "A" else "B"

        /**
         * What the notice line says after a song was loaded onto [deck]: call it under the mixer
         * lock, right after the load and any sync, and show it with [say]. [synced] is the tempo
         * change the sync made, or null when the song wasn't synced. [computer]: "Click", not "Tap".
         */
        fun loadedNotice(mixer: Mixer, deck: Int, synced: Double?, computer: Boolean = false): String {
            val tap = if (computer) "Click" else "Tap"
            val d = mixer.decks[deck]; val o = mixer.decks[1 - deck]
            val song = "\"${d.title}\""
            val name = deckName(deck)
            val otherLive = o.playing && !o.previewing
            return when {
                otherLive && synced != null -> {
                    val pct = Math.round(synced * 1000) / 10.0
                    val signed = (if (pct < 0) "−" else "+") + "%.1f".format(abs(pct))
                    "$song is on deck $name, synced ($signed%). $tap MIX, or press ▶ when the moment's right."
                }
                otherLive && d.bpm > 0 && o.bpm > 0 ->
                    "$song is on deck $name at its own speed (${"%.1f".format(d.effectiveBpm)} BPM). $tap SYNC to match deck ${deckName(1 - deck)}."
                else -> "$song is ready on deck $name. Press ▶ to play."
            }
        }
    }
}
