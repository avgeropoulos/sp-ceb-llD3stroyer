package com.mixdeck.desktop

import com.mixdeck.Net
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Where this copy of MixDeck is installed, and whether it can replace itself with a new version. */
object Install {
    private val os = System.getProperty("os.name").lowercase()
    val isMac = os.contains("mac")
    val isWindows = os.contains("win")

    /** The folder (Windows, Linux) or .app bundle (Mac) that an update replaces, or null when not packaged. */
    val root: File? = run {
        // The packaged launcher says where it is; failing that, the bundled Java runtime does
        // (MixDeck/runtime, MixDeck/lib/runtime, MixDeck.app/Contents/runtime/Contents/Home).
        System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile }?.takeIf { it.isFile }?.let(::rootOf)
            ?: File(System.getProperty("java.home")).absoluteFile.let { home ->
                when {
                    isMac -> home.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") && home.path.endsWith("Contents/runtime/Contents/Home") }
                    isWindows -> home.parentFile?.takeIf { home.name == "runtime" }
                    else -> home.parentFile?.parentFile?.takeIf { home.name == "runtime" && home.parentFile.name == "lib" }
                }
            }?.takeIf { launcherIn(it) != null }
    }

    /** MixDeck/MixDeck.exe, MixDeck/bin/MixDeck, MixDeck.app/Contents/MacOS/MixDeck → the folder or bundle. */
    fun rootOf(launcher: File): File? = when {
        isMac -> launcher.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") }
        isWindows -> launcher.parentFile
        else -> launcher.parentFile?.parentFile
    }

    /** The launcher's path inside [root], e.g. "bin/MixDeck". */
    fun launcherIn(root: File): File? = when {
        isMac -> File(root, "Contents/MacOS").listFiles()?.firstOrNull { it.isFile && it.canExecute() }
        isWindows -> root.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
        else -> File(root, "bin").listFiles()?.firstOrNull { it.isFile && it.canExecute() }
    }

    /** Null when [root] can be updated in place; otherwise why not, in words for the user. */
    fun problem(root: File? = this.root): String? = when {
        root == null -> "This copy of MixDeck wasn't installed from a download, so it can't update itself."
        isMac && root.path.contains("/AppTranslocation/") ->
            "macOS is running MixDeck from a temporary place. Drag MixDeck into your Applications folder, open it from there, then update."
        isMac && root.path.startsWith("/Volumes/") && !writable(root.parentFile) ->
            "MixDeck is running from the disk image. Drag it into your Applications folder, open it from there, then update."
        !writable(root.parentFile) || !writable(root) ->
            "MixDeck can't change files in ${root.parentFile}. Move the MixDeck ${if (isMac) "app" else "folder"} somewhere you own (such as your " +
                "${if (isMac) "Applications" else "Documents"} folder), open it from there, then update."
        else -> null
    }

    private fun writable(dir: File?): Boolean =
        dir != null && dir.isDirectory && runCatching { File.createTempFile(".mixdeck", ".tmp", dir).delete() }.getOrDefault(false)

    /** Unpacks a downloaded MixDeck package (.tar.gz, .zip or .dmg) into [into]; returns the new folder or .app inside it. */
    fun unpack(pkg: File, into: File): File {
        into.deleteRecursively(); into.mkdirs()
        val name = pkg.name.lowercase()
        when {
            name.endsWith(".zip") -> unzip(pkg, into)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> run("tar", "-xzf", pkg.path, "-C", into.path)
            name.endsWith(".dmg") -> {
                val mnt = Files.createTempDirectory("mixdeck-dmg").toFile()
                try {
                    run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mnt.path, pkg.path)
                    val app = mnt.listFiles()?.firstOrNull { it.name.endsWith(".app") } ?: throw IOException("No app in the update")
                    run("ditto", app.path, File(into, app.name).path)
                } finally {
                    runCatching { run("hdiutil", "detach", mnt.path, "-force") }
                    mnt.delete()
                }
            }
            else -> throw IOException("Unknown update file ${pkg.name}")
        }
        val root = into.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("The update didn't unpack properly")
        if (launcherIn(root) == null) throw IOException("The update has no MixDeck in it")
        return root
    }

    private fun unzip(zip: File, into: File) {
        val base = into.canonicalFile
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val f = File(base, e.name).canonicalFile
                if (!f.path.startsWith(base.path + File.separator)) throw IOException("Bad path in update: ${e.name}")
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile.mkdirs()
                z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw IOException("${cmd[0]} took too long") }
        if (p.exitValue() != 0) throw IOException("${cmd[0]} failed: ${out.trim().take(300)}")
    }

    /**
     * Starts a small helper that waits for this process to quit, puts [newRoot] where [root] is
     * (keeping the old version if anything goes wrong), removes [stage] and, if [relaunch], starts
     * MixDeck again with [args]. Its log goes to [log].
     */
    fun startSwap(root: File, newRoot: File, stage: File, relaunch: Boolean, args: List<String>, log: File) {
        val pid = ProcessHandle.current().pid()
        log.parentFile.mkdirs()
        val launcher = launcherIn(newRoot)?.relativeTo(newRoot)?.path ?: throw IOException("No launcher in the update")
        val launch = File(root, launcher)
        val cmd: List<String> = if (isWindows) {
            fun q(s: String) = "'" + s.replace("'", "''") + "'"
            val script = File(log.parentFile, "swap.ps1")
            val start = if (!relaunch) "" else if (args.isEmpty()) "Start-Process -FilePath ${q(launch.path)}"
                else "Start-Process -FilePath ${q(launch.path)} -ArgumentList @(${args.joinToString(", ") { q("\"" + it + "\"") }})"
            script.writeText("""
                |# MixDeck update: wait for MixDeck to quit, swap in the new version, start it again.
                |${'$'}old = ${q(root.path)}
                |${'$'}new = ${q(newRoot.path)}
                |${'$'}stage = ${q(stage.path)}
                |${'$'}bak = ${'$'}old + '.old'
                |try { Wait-Process -Id $pid -Timeout 120 -ErrorAction Stop } catch {}
                |Start-Sleep -Milliseconds 500
                |if (Test-Path -LiteralPath ${'$'}bak) { Remove-Item -LiteralPath ${'$'}bak -Recurse -Force -ErrorAction SilentlyContinue }
                |${'$'}moved = ${'$'}false
                |for (${'$'}i = 0; ${'$'}i -lt 40 -and -not ${'$'}moved; ${'$'}i++) {
                |  try { Move-Item -LiteralPath ${'$'}old -Destination ${'$'}bak -ErrorAction Stop; ${'$'}moved = ${'$'}true } catch { Start-Sleep -Milliseconds 500 }
                |}
                |if (${'$'}moved) {
                |  try { Move-Item -LiteralPath ${'$'}new -Destination ${'$'}old -ErrorAction Stop; 'updated'; Remove-Item -LiteralPath ${'$'}bak -Recurse -Force -ErrorAction SilentlyContinue }
                |  catch { "swap failed: ${'$'}_"; Move-Item -LiteralPath ${'$'}bak -Destination ${'$'}old -ErrorAction SilentlyContinue }
                |} else { 'MixDeck is still in use, kept the old version' }
                |Remove-Item -LiteralPath ${'$'}stage -Recurse -Force -ErrorAction SilentlyContinue
                |$start
                |""".trimMargin())
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.path)
        } else {
            fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
            val script = File(log.parentFile, "swap.sh")
            val start = when {
                !relaunch -> ""
                isMac && args.isEmpty() -> "open \"\$OLD\""
                else -> "nohup ${q(launch.path)} ${args.joinToString(" ") { q(it) }} >/dev/null 2>&1 &"
            }
            script.writeText("""
                |#!/bin/sh
                |# MixDeck update: wait for MixDeck to quit, swap in the new version, start it again.
                |trap '' HUP
                |OLD=${q(root.path)}
                |NEW=${q(newRoot.path)}
                |STAGE=${q(stage.path)}
                |n=0
                |while kill -0 $pid 2>/dev/null; do
                |  n=${'$'}((n+1)); if [ "${'$'}n" -gt 480 ]; then echo "MixDeck didn't quit"; exit 1; fi
                |  sleep 0.25
                |done
                |rm -rf "${'$'}OLD.old"
                |if mv "${'$'}OLD" "${'$'}OLD.old"; then
                |  if mv "${'$'}NEW" "${'$'}OLD"; then echo updated; rm -rf "${'$'}OLD.old"
                |  else echo "swap failed, kept the old version"; mv "${'$'}OLD.old" "${'$'}OLD"; fi
                |else echo "couldn't move the old version"; fi
                |rm -rf "${'$'}STAGE"
                |${if (isMac) "xattr -dr com.apple.quarantine \"\$OLD\" 2>/dev/null" else ""}
                |$start
                |""".trimMargin())
            listOf("/bin/sh", script.path)
        }
        process(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
    }

    /** Leftovers from an earlier update (an interrupted download, the previous version's backup). */
    fun cleanUp() {
        val r = root ?: return
        runCatching { File(r.parentFile, stageName(r)).takeIf { it.exists() }?.deleteRecursively() }
        runCatching { File(r.path + ".old").takeIf { it.exists() }?.deleteRecursively() }
    }

    /** The new version is unpacked next to the installed one, so the swap is a quick rename. */
    fun stageName(root: File) = "." + root.name + "-update"

    /**
     * A process that may start MixDeck again. The packaged launcher marks its environment (e.g.
     * _JPACKAGE_LAUNCHER) to recognise its own restart; a new MixDeck that inherited the mark would
     * take its arguments for Java options and fail to start, so the mark is removed.
     */
    fun process(cmd: List<String>): ProcessBuilder =
        ProcessBuilder(cmd).also { pb -> pb.environment().keys.removeIf { it.contains("JPACKAGE", ignoreCase = true) } }

    /** The launcher's marks in this process's environment (for the self-test log). */
    fun launcherMarks(): List<String> = System.getenv().keys.filter { it.contains("JPACKAGE", ignoreCase = true) }
}

/**
 * Updates MixDeck on the computer from inside the app, like the phone app: it checks GitHub for a
 * newer build, downloads it in the background while you keep mixing, unpacks it next to this one,
 * and when you say so, closes, swaps in the new version and opens it again.
 */
class DesktopUpdater(private val app: App) {
    data class Release(val code: Int, val name: String, val notes: String)

    private val base = System.getenv("MIXDECK_UPDATE_URL")?.trimEnd('/')?.plus("/")
        ?: "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/mixdeck-latest/"
    private val prefs = Preferences.userRoot().node("mixdeck")

    /** A newer version on GitHub, once a check found one. */
    @Volatile var available: Release? = null; private set
    /** Downloaded and unpacked, waiting for a restart. */
    @Volatile var ready: Pair<Release, File>? = null; private set
    @Volatile private var downloading = false

    /** True when there's something for the ☰ badge to point at. */
    val hasNews get() = available != null || ready != null

    fun check(manual: Boolean) {
        thread(isDaemon = true, name = "update check") {
            val r = runCatching {
                val o = JSONObject(Net.getText(base + "version.json?t=" + System.currentTimeMillis()))
                Release(o.optInt("versionCode"), o.optString("versionName"), o.optString("notes"))
            }
            SwingUtilities.invokeLater {
                val rel = r.getOrNull()
                when {
                    rel == null -> if (manual) app.error("Couldn't check for updates: ${r.exceptionOrNull()?.message}")
                    rel.code > Version.code -> {
                        available = rel
                        app.repaint()
                        // Never interrupt the music with a popup: the ☰ badge says it's there.
                        if (manual || !app.musicPlaying()) offer()
                    }
                    manual -> app.info("Up to date", "MixDeck is up to date (version ${Version.name}).")
                }
            }
        }
    }

    /** What the ☰ menu's update item says, or null when there's no update. */
    fun menuLabel(): String? = when {
        ready != null -> "⬆ Restart to finish updating to ${ready!!.first.name}"
        downloading -> "⬆ Downloading the update…"
        available != null -> "⬆ Update to ${available!!.name}"
        else -> null
    }

    /** The ☰ menu's update item. */
    fun menuPick() {
        when {
            ready != null -> askRestart()
            downloading -> progress?.isVisible = true
            available != null -> offer()
        }
    }

    private fun offer() {
        val rel = available ?: return
        val problem = Install.problem()
        val notes = rel.notes.removePrefix("What's new: ").trim()
        val msg = "MixDeck ${rel.name} is out (you have ${Version.name}).\n\nWhat's new: $notes"
        if (problem != null) {
            val c = JOptionPane.showOptionDialog(app.frame, "$msg\n\n$problem", "Update available", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, arrayOf("Download it in my browser", "Later"), "Later")
            if (c == 0) browse(base + assetFor(Version.platform))
            return
        }
        val c = JOptionPane.showOptionDialog(app.frame,
            "$msg\n\nIt downloads in the background while you keep mixing (about 80 MB), then you choose when to restart.",
            "Update available", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
            arrayOf("Update", "Later"), "Update")
        if (c == 0) download(rel)
    }

    private var progress: JDialog? = null

    private fun download(rel: Release) {
        if (downloading) return
        val root = Install.root ?: return
        downloading = true
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val bar = JProgressBar(0, 100).apply { isStringPainted = true }
        val label = JLabel("Downloading MixDeck ${rel.name}…")
        val dlg = JDialog(app.frame, "Updating MixDeck", false).apply {
            val p = JPanel(BorderLayout(8, 8)).apply { border = BorderFactory.createEmptyBorder(14, 14, 10, 14) }
            p.add(label, BorderLayout.NORTH); p.add(bar, BorderLayout.CENTER)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttons.add(JButton("Hide").apply { addActionListener { isVisible = false } })
            buttons.add(JButton("Cancel").apply { addActionListener { cancelled.set(true); isVisible = false } })
            p.add(buttons, BorderLayout.SOUTH)
            contentPane.add(p); setSize(440, 150); setLocationRelativeTo(app.frame)
            defaultCloseOperation = JDialog.HIDE_ON_CLOSE
        }
        progress = dlg
        dlg.isVisible = true
        app.repaint()
        thread(isDaemon = true, name = "update download") {
            val asset = assetFor(Version.platform)
            val dir = File(Places.data, "update").apply { mkdirs() }
            val pkg = File(dir, asset)
            val stage = File(root.parentFile, Install.stageName(root))
            val r = runCatching {
                Net.download(base + asset, pkg) { p ->
                    if (cancelled.get()) throw CancellationException()
                    SwingUtilities.invokeLater { bar.value = (p * 100).toInt() }
                }
                SwingUtilities.invokeLater { label.text = "Unpacking…"; bar.isIndeterminate = true }
                Install.unpack(pkg, stage)
            }
            pkg.delete()
            SwingUtilities.invokeLater {
                downloading = false
                dlg.dispose(); progress = null
                r.onSuccess { newRoot ->
                    ready = rel to newRoot
                    prefs.putInt("updatedFrom", Version.code); prefs.put("updateNotes", rel.notes)
                    runCatching { prefs.flush() }
                    app.repaint()
                    askRestart()
                }.onFailure { e ->
                    stage.deleteRecursively()
                    app.repaint()
                    if (e !is CancellationException) app.error("The update didn't download: ${e.message ?: e.javaClass.simpleName}\n" +
                        "Try again from ☰ → Update, or download it in your browser.")
                }
            }
        }
    }

    private fun askRestart() {
        val (rel, _) = ready ?: return
        val playing = app.musicPlaying()
        val c = JOptionPane.showOptionDialog(app.frame,
            "MixDeck ${rel.name} is ready.\nMixDeck will close, update and open again (about 10 seconds)." +
                (if (playing) "\n\nThis stops the music." else ""),
            "Restart to update", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            arrayOf("Restart now", "When I quit"), if (playing) "When I quit" else "Restart now")
        if (c == 0) app.quit(restart = true)
    }

    /** Called as MixDeck quits: if an update is waiting, hand over to the helper that swaps it in. */
    fun applyOnQuit(relaunch: Boolean) {
        val (_, newRoot) = ready ?: return
        val root = Install.root ?: return
        runCatching {
            Install.startSwap(root, newRoot, newRoot.parentFile, relaunch, emptyList(), File(Places.data, "update/update.log"))
        }.onFailure { runCatching { File(Places.data, "update/update.log").appendText("couldn't start: $it\n") } }
    }

    /** After an update: say what's new, once. */
    fun welcomeBack() {
        val from = prefs.getInt("updatedFrom", 0)
        if (from == 0) return
        val notes = prefs.get("updateNotes", "")
        prefs.remove("updatedFrom"); prefs.remove("updateNotes"); runCatching { prefs.flush() }
        if (Version.code > from) app.info("MixDeck updated", "You're now on MixDeck ${Version.name}.\n\n${notes.ifEmpty { "" }}".trim())
    }

    companion object {
        fun assetFor(platform: String) = when {
            platform.startsWith("macosx-arm64") -> "MixDeck-mac-apple-silicon.dmg"
            platform.startsWith("macosx") -> "MixDeck-mac-intel.dmg"
            platform.startsWith("windows") -> "MixDeck-windows.zip"
            else -> "MixDeck-linux.tar.gz"
        }
    }
}

/**
 * `MixDeck --selftest-update <package> <work dir>`: checks, on this OS, that an installed MixDeck can
 * swap itself for a new version and start again. CI runs it on every OS with the package it built:
 *  1. unpack the package into <work>/install as if it were installed, mark it, and start that copy with
 *     --selftest-update-apply;
 *  2. that copy (now the "installed" MixDeck) unpacks the package next to itself and quits, leaving the
 *     helper to swap it in and start it with --selftest-updated;
 *  3. the new copy writes <work>/result.txt, which CI checks.
 */
object UpdateSelfTest {
    fun phase1(pkg: File, work: File): Int {
        work.mkdirs()
        val root = Install.unpack(pkg, File(work, "install"))
        File(root, "old-version.txt").writeText("this file is gone once the update has swapped the folder")
        val launcher = Install.launcherIn(root) ?: return fail(work, "no launcher in ${root.path}")
        println("launcher marks removed for the copy: ${Install.launcherMarks()}")
        Install.process(listOf(launcher.path, "--selftest-update-apply", pkg.absolutePath, work.absolutePath))
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(work, "phase2.log"))).start()
        println("started the installed copy: ${launcher.path}")
        return 0
    }

    fun phase2(pkg: File, work: File): Int {
        val root = Install.root ?: return fail(work, "phase 2 isn't running from a packaged app")
        Install.problem(root)?.let { return fail(work, "can't update in place: $it") }
        val stage = File(root.parentFile, Install.stageName(root))
        val newRoot = Install.unpack(pkg, stage)
        Install.startSwap(root, newRoot, stage, relaunch = true,
            args = listOf("--selftest-updated", work.absolutePath), log = File(work, "swap.log"))
        println("helper started; quitting so it can swap ${root.path}")
        return 0
    }

    fun phase3(work: File): Int {
        val root = Install.root ?: return fail(work, "the restarted copy isn't a packaged app")
        val oldMarker = File(root, "old-version.txt").exists()
        val backup = File(root.path + ".old").exists()
        val stage = File(root.parentFile, Install.stageName(root)).exists()
        val ok = !oldMarker && !backup && !stage
        File(work, "result.txt").writeText(
            (if (ok) "OK" else "FAIL") + " version=${Version.code} root=${root.path} oldMarker=$oldMarker backup=$backup stage=$stage\n")
        return if (ok) 0 else 1
    }

    private fun fail(work: File, msg: String): Int {
        work.mkdirs(); File(work, "result.txt").writeText("FAIL $msg\n"); System.err.println(msg); return 1
    }
}
