package com.sitarsitara

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.sitarsitara.core.Scale
import com.sitarsitara.core.Script
import com.sitarsitara.core.Swara

/** The first screen: play along with a song, play Sitar Superstar, read up on sargam, and update the app. */
class MainActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val updater by lazy { Updater(this) }
    private lateinit var updateButton: Button
    private var latestBuild: Long? = null
    private var updating = false
    /** Set when the game's "Update" was tapped: start updating once the check is done. */
    private var updateNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = NIGHT
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NIGHT) }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        root.addView(ui.titleBar("Sitar Sitara", "Sargam for any song, and a game to play it" + (version?.let { "  ·  v$it" } ?: "")))
        root.addView(ToranView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(14)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(20)) }
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(big("🎵  Play along with a song", main = true) { startActivity(Intent(this, SongActivity::class.java)) })
        col.addView(ui.text("Search YouTube or paste a link. The app listens for the sitar and writes it as sargam on the taal, " +
            "lit up as the video plays.", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(6), ui.px(4), ui.px(6)) })
        col.addView(big("🎮  Sitar Superstar") { startActivity(Intent(this, com.sitarsitara.hero.HeroMenuActivity::class.java)) })
        col.addView(ui.text("The game: tap the swaras as they come down the sitar's neck. Wah!", 13f, MUTED)
            .apply { setPadding(ui.px(4), ui.px(6), ui.px(4), ui.px(6)) })

        updateButton = ui.chip("Checking for updates…") { onUpdateClicked() }
        col.addView(updateButton, ui.wide(10))

        col.addView(ui.label("How the sargam is written"))
        val swaras = (0 until 12).map { Swara(it, 0) }
        col.addView(ui.text(
            swaras.joinToString("   ") { it.text(Script.LATIN) } + "\n" +
                swaras.joinToString("   ") { it.text(Script.DEVANAGARI) } + "\n\n" +
                "Capitals (and plain Devanagari) are shuddha; small letters (a line below) are komal; M (a stroke above) is tivra Ma. " +
                "A dot above a swara is the taar (upper) saptak, a dot below the mandra (lower). " +
                "~ before a note: reached by meend, without a new stroke. * after it: fast strokes (diri). " +
                "Notes in one box share a matra; – holds the note before. X marks sam, numbers the talis, 0 the khali.",
            14f, SOFT,
        ).apply { setPadding(ui.px(4), 0, ui.px(4), ui.px(8)) })

        col.addView(ui.label("The scales it can tell"))
        col.addView(ui.text(Scale.entries.joinToString("\n") { s ->
            s.title + ":  " + s.degrees.joinToString(" ") { Swara(it, 0).text(Script.LATIN) } + "   (" + s.ragas.joinToString(", ") + ")"
        } + "\n\nA scale is not a raga: ragas sharing these notes differ in their phrases, which the app doesn't judge.", 13f, SOFT)
            .apply { setPadding(ui.px(4), 0, ui.px(4), ui.px(8)) })

        setContentView(root)
        fitSystemBars(this, root)
        updateNow = intent.getBooleanExtra(EXTRA_UPDATE_NOW, false)
        checkForUpdate()
        handleInstallStatus(intent)
    }

    private fun big(label: String, main: Boolean = false, onClick: () -> Unit) = ui.chip(label, onClick).apply {
        textSize = 18f
        minHeight = ui.px(62)
        ui.style(this, main)
        layoutParams = ui.wide(8)
    }

    private fun checkForUpdate() {
        updateButton.text = "Checking for updates…"
        ui.style(updateButton, false)
        updater.checkLatest { latest ->
            latestBuild = latest
            if (!updating) showUpdateState()
            if (updateNow) { updateNow = false; onUpdateClicked() }
        }
    }

    private fun showUpdateState() {
        val latest = latestBuild
        when {
            latest == null -> { updateButton.text = "Couldn't check for updates · tap to retry"; ui.style(updateButton, false) }
            latest > updater.installedBuild -> { updateButton.text = "⬆  Update to v1.$latest"; ui.style(updateButton, true) }
            else -> { updateButton.text = "✓ Up to date (v1.${updater.installedBuild}) · tap to check again"; ui.style(updateButton, false) }
        }
    }

    private fun onUpdateClicked() {
        val latest = latestBuild
        if (updating) return
        if (latest == null || latest <= updater.installedBuild) { checkForUpdate(); return }
        if (!updater.ensureCanInstall()) {
            Toast.makeText(this, "Allow Sitar Sitara to install updates, then come back and tap Update again", Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        updater.install(
            progress = { pct -> updateButton.text = "Downloading v1.$latest…  $pct%" },
            failed = { msg ->
                updating = false
                showUpdateState()
                Toast.makeText(this, "Update failed: $msg", Toast.LENGTH_LONG).show()
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
        if (intent.getBooleanExtra(EXTRA_UPDATE_NOW, false)) { updateNow = true; checkForUpdate() }
    }

    /** Android reports on the update here: first it needs you to confirm, then it says how it went. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> updating = false
            else -> {
                updating = false
                showUpdateState()
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"
                Toast.makeText(this, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** Start updating straight away (from the game's update banner). */
        const val EXTRA_UPDATE_NOW = "update_now"
    }
}
