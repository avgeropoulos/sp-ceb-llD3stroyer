package com.thermopylae

import kotlin.math.PI
import kotlin.math.roundToLong

/**
 * Android-free logic used by the host classes, kept here so it can be unit-tested on a plain
 * JVM: asset routing, the MIME table, the sensor line format, vibration patterns and the
 * orientation mapping.
 *
 * Every number that ends up in a string is written with [StringBuilder.append] (never
 * `String.format`), so the output is the same on Greek or German locales with decimal commas.
 */
object HostLogic {
    const val HOST = "appassets.androidplatform.net"
    const val START_URL = "https://$HOST/game/index.html"

    /** Oldest WebView/Chrome major version that runs the game (DESIGN.md §2.1 engine check). */
    const val MIN_CHROME = 94

    /** Sensor lines kept between polls (2.5 s at 200 Hz). */
    const val RING_LINES = 512

    /** Longest single vibration and longest pattern step, in ms. */
    const val MAX_VIBRATION_MS = 400

    /** Most values accepted in a vibration pattern. */
    const val MAX_PATTERN_VALUES = 16

    /** Largest app update the updater will download (the APK is about 12 MB). */
    const val MAX_UPDATE_BYTES = 48L * 1024 * 1024

    /** The host's own SharedPreferences file, and its key for the orientation the game last asked for. */
    const val HOST_PREFS = "thermopylae.host"
    const val PREF_ORIENTATION = "orientation"

    /** Where a request to the asset server goes: an asset, or an empty error response. */
    sealed class Route {
        data class Asset(val name: String) : Route()
        data class Error(val status: Int, val reason: String) : Route()
    }

    val FORBIDDEN = Route.Error(403, "Forbidden")
    val NOT_FOUND = Route.Error(404, "Not Found")
    val METHOD_NOT_ALLOWED = Route.Error(405, "Method Not Allowed")

    /** True for `https://appassets.androidplatform.net` URLs, the only origin the game uses. */
    fun isOwnOrigin(scheme: String?, host: String?): Boolean =
        scheme.equals("https", ignoreCase = true) && host.equals(HOST, ignoreCase = true)

    /**
     * Maps a request to an asset. [path] is the decoded URL path (`Uri.getPath()`), so query
     * strings are already gone and `%2e%2e` is caught as `..`.
     * `/game/<p>` → `game/<p>`, and a path ending in `/` gets `index.html`.
     */
    fun route(scheme: String?, host: String?, path: String?, method: String?): Route {
        if (!isOwnOrigin(scheme, host)) return FORBIDDEN
        val p = path ?: ""
        if (p.contains("..")) return FORBIDDEN
        if (!"GET".equals(method, ignoreCase = true)) return METHOD_NOT_ALLOWED
        if (!p.startsWith("/game/")) return NOT_FOUND
        val name = p.substring(1)
        return Route.Asset(if (name.endsWith('/')) name + "index.html" else name)
    }

    /** MIME type from the file extension (a fixed table; never MimeTypeMap). */
    fun mimeType(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot < path.lastIndexOf('/')) return "application/octet-stream"
        return when (path.substring(dot + 1).lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ogg", "opus" -> "audio/ogg"
            "woff2" -> "font/woff2"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** "utf-8" for text, JSON and SVG; null for binary types. */
    fun encodingFor(mime: String): String? =
        if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") "utf-8" else null

    private val CHROME_VERSION = Regex("""Chrome/(\d+)""")

    /** Chrome major version from a user agent, or 0 if there is none. */
    fun chromeMajor(userAgent: String?): Int {
        if (userAgent == null) return 0
        return CHROME_VERSION.find(userAgent)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Appends one sensor line, `t,gx,gy,gz,ax,ay,az,Ax,Ay,Az,gvx,gvy,gvz,rot\n`
     * (a = linear acceleration, A = raw accelerometer, gv = gravity). Missing values are NaN,
     * which JavaScript's `Number()` reads back as NaN.
     */
    fun appendSensorLine(
        sb: StringBuilder,
        t: Double,
        gyro: FloatArray,
        linear: FloatArray,
        accel: FloatArray,
        gravity: FloatArray,
        rotation: Int,
    ): StringBuilder {
        sb.append(t)
        appendTriple(sb, gyro)
        appendTriple(sb, linear)
        appendTriple(sb, accel)
        appendTriple(sb, gravity)
        sb.append(',').append(rotation).append('\n')
        return sb
    }

    private fun appendTriple(sb: StringBuilder, v: FloatArray) {
        sb.append(',').append(v[0]).append(',').append(v[1]).append(',').append(v[2])
    }

    /** Copies the first three values of [src] into [dst], or NaN-fills it if there aren't three. */
    fun copy3(src: FloatArray, dst: FloatArray) {
        if (src.size >= 3) {
            dst[0] = src[0]
            dst[1] = src[1]
            dst[2] = src[2]
        } else {
            dst.fill(Float.NaN)
        }
    }

    /** The orientation family the game asked for: "portrait", or "landscape" for anything else. */
    fun orientationFamily(orientation: String?): String =
        if (orientation == "portrait") "portrait" else "landscape"

    /**
     * The `ActivityInfo.SCREEN_ORIENTATION_*` value for a `lockOrientation(lock, orientation)`
     * request (docs-hilt-grip.md §A.4). Portrait (the hilt grip) is always portrait-primary, locked
     * or not. Landscape follows the sensor between both sides unless [lock] (motion play), which
     * keeps the side the display already shows; the rotation number is never used to pick a side,
     * since rotation 0 is landscape on natural-landscape tablets. Only when the display is still
     * portrait as a locked landscape fight starts (the grip was just changed) does it ask for plain
     * landscape, because LOCKED would lock portrait.
     */
    fun requestedOrientation(lock: Boolean, orientation: String?, displayLandscape: Boolean): Int =
        when {
            orientation == "portrait" -> 1 // SCREEN_ORIENTATION_PORTRAIT (portrait-primary, never reverse)
            !lock -> 6 // SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            displayLandscape -> 14 // SCREEN_ORIENTATION_LOCKED: keep the current landscape side
            else -> 0 // SCREEN_ORIENTATION_LANDSCAPE: still portrait (the grip was just changed)
        }

    /** Clamps a one-shot vibration to 1..400 ms. */
    fun vibrationMs(ms: Int): Long = ms.coerceIn(1, MAX_VIBRATION_MS).toLong()

    /** Clamps a vibration amplitude to 1..255. */
    fun vibrationAmplitude(amplitude: Int): Int = amplitude.coerceIn(1, 255)

    /**
     * Parses a vibration pattern "delay,on,off,on,…" in ms. Keeps at most 16 values, rounds and
     * clamps each to 0..400, tolerates spaces, `[ ]` and a trailing comma, and returns null if
     * a value isn't a finite number or nothing would vibrate.
     */
    fun parsePattern(csv: String?): LongArray? {
        if (csv == null) return null
        val body = csv.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return null
        val parts = body.split(',')
        val count = if (parts.last().isBlank()) parts.size - 1 else parts.size
        val n = minOf(count, MAX_PATTERN_VALUES)
        val out = LongArray(n)
        var onTime = 0L
        for (i in 0 until n) {
            val v = parts[i].trim().toDoubleOrNull() ?: return null
            if (!v.isFinite()) return null
            val ms = v.coerceIn(0.0, MAX_VIBRATION_MS.toDouble()).roundToLong()
            out[i] = ms
            if (i % 2 == 1) onTime += ms
        }
        return if (onTime > 0) out else null
    }

    /**
     * Gyroscope range in rad/s from `Sensor.getMaximumRange()`, or null if implausible. Some
     * HALs report deg/s (2000 rather than 34.9), so values above 150 are converted.
     */
    fun gyroRange(maximumRange: Float?): Float? {
        val r = maximumRange ?: return null
        if (!r.isFinite() || r < 4f) return null
        return if (r > 150f) (r * PI / 180.0).toFloat() else r
    }

    /** Accelerometer range in m/s², or null if implausible (below 1 g). */
    fun accRange(maximumRange: Float?): Float? {
        val r = maximumRange ?: return null
        return if (r.isFinite() && r >= 9f) r else null
    }

    /** `{"gyroRange":…,"accRange":…,"rateHz":…}`; unknown ranges are null, an unmeasured rate is 0. */
    fun motionInfoJson(gyroRange: Float?, accRange: Float?, rateHz: Double): String {
        val sb = StringBuilder(64)
        sb.append("{\"gyroRange\":")
        if (gyroRange != null) sb.append(gyroRange) else sb.append("null")
        sb.append(",\"accRange\":")
        if (accRange != null) sb.append(accRange) else sb.append("null")
        sb.append(",\"rateHz\":")
        if (rateHz.isFinite() && rateHz > 0) sb.append((rateHz * 10).roundToLong() / 10.0) else sb.append(0)
        return sb.append('}').toString()
    }

    /**
     * True for the only places app updates may come from: HTTPS on github.com (releases) or its
     * download hosts (`*.githubusercontent.com`, where release downloads redirect).
     */
    fun isTrustedDownload(url: String?): Boolean {
        if (url == null) return false
        val m = Regex("""^https://([A-Za-z0-9.-]+)(?::443)?(/[^\s]*)?$""").matchEntire(url) ?: return false
        val host = m.groupValues[1].lowercase()
        return host == "github.com" || (host.endsWith(".githubusercontent.com") && !host.startsWith("."))
    }

    /** Lower-case hex of [bytes]. */
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return sb.toString()
    }

    /** Appends [v] with exactly two decimals and a dot, whatever the locale. */
    fun appendFixed2(sb: StringBuilder, v: Float): StringBuilder {
        if (!v.isFinite()) return sb.append("0.00")
        var c = (v * 100.0).roundToLong()
        if (c < 0) {
            sb.append('-')
            c = -c
        }
        sb.append(c / 100).append('.')
        val frac = (c % 100).toInt()
        if (frac < 10) sb.append('0')
        return sb.append(frac)
    }

    /** JavaScript that sets the CSS variables `--sai-left/top/right/bottom` to CSS px. */
    fun insetsScript(left: Float, top: Float, right: Float, bottom: Float): String {
        val sb = StringBuilder(256)
        sb.append("(function(){var d=document.documentElement;if(!d)return;var s=d.style;")
        appendVar(sb, "left", left)
        appendVar(sb, "top", top)
        appendVar(sb, "right", right)
        appendVar(sb, "bottom", bottom)
        return sb.append("})()").toString()
    }

    private fun appendVar(sb: StringBuilder, side: String, px: Float) {
        sb.append("s.setProperty('--sai-").append(side).append("','")
        appendFixed2(sb, px)
        sb.append("px');")
    }

    /**
     * Fixed-size ring of sensor lines that drops the oldest when full. Not thread-safe: the
     * bridge guards it with its own lock.
     */
    class LineRing(val capacity: Int = RING_LINES) {
        private val lines = arrayOfNulls<String>(capacity)
        private val out = StringBuilder(capacity * 8)
        private var head = 0

        /** Lines currently held. */
        var size = 0
            private set

        /** Lines dropped because the ring was full, since creation. */
        var dropped = 0L
            private set

        fun push(line: String) {
            lines[(head + size) % capacity] = line
            if (size < capacity) {
                size++
            } else {
                head = (head + 1) % capacity
                dropped++
            }
        }

        /** Returns every line, oldest first, concatenated, and empties the ring ("" if empty). */
        fun drain(): String {
            if (size == 0) return ""
            out.setLength(0)
            for (k in 0 until size) {
                val i = (head + k) % capacity
                out.append(lines[i])
                lines[i] = null
            }
            head = 0
            size = 0
            return out.toString()
        }

        fun clear() {
            lines.fill(null)
            head = 0
            size = 0
        }
    }
}
