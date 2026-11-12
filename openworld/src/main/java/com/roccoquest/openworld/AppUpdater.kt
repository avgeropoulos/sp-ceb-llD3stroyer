package com.roccoquest.openworld

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object UpdateStatus {
    @Volatile var text="Check for a newer version without leaving the game."
    val busy=AtomicBoolean(false)
}

/** Explicit receiver survives Activity recreation while Android confirms the install. */
class UpdateReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java)
                    else { @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) }
                if(confirm!=null) {
                    try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); UpdateStatus.text="Confirm Update in the Android installer." }
                    catch(_:Exception) { UpdateStatus.text="Could not open installer. Tap Update to retry."; UpdateStatus.busy.set(false) }
                } else { UpdateStatus.text="Installer unavailable. Tap Update to retry."; UpdateStatus.busy.set(false) }
            }
            else -> {
                UpdateStatus.text=when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,-1)) {
                    PackageInstaller.STATUS_SUCCESS -> "Update installed!"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled. Your current game is unchanged."
                    else -> "Installation failed. Tap Update to try again."
                }
                UpdateStatus.busy.set(false)
            }
        }
    }
}

class AppUpdater(context:Context) {
    private val app=context.applicationContext
    fun start() {
        if(!UpdateStatus.busy.compareAndSet(false,true)) return
        thread(name="openworld-update") {
            var handedToInstaller=false
            val apk=File(app.cacheDir,"openworld-update.apk")
            try {
                UpdateStatus.text="Checking for an update..."
                val metadata=connection(BASE+"update.json")
                val json=try { metadata.inputStream.use { input ->
                    val data=input.readBytesLimited(8192); JSONObject(String(data,Charsets.UTF_8))
                } } finally { metadata.disconnect() }
                val newest=json.getLong("versionCode")
                if(newest<=BuildConfig.VERSION_CODE) { UpdateStatus.text="You're up to date! Version ${BuildConfig.VERSION_NAME}"; return@thread }
                val size=json.getLong("size")
                val hash=json.getString("sha256")
                require(size in 1..MAX_APK && hash.matches(Regex("[0-9a-f]{64}")))
                if(Build.VERSION.SDK_INT>=26 && !app.packageManager.canRequestPackageInstalls()) {
                    UpdateStatus.text="Allow updates for this app, return, then tap Update."
                    Handler(Looper.getMainLooper()).post {
                        try { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch(_:Exception) { UpdateStatus.text="Enable 'Install unknown apps' for Team Up: Open World in Settings." }
                    }
                    return@thread
                }
                UpdateStatus.text="Downloading update... 0%"
                val download=connection(BASE+"TeamUpOpenWorld.apk")
                val digest=MessageDigest.getInstance("SHA-256")
                var done=0L
                try { download.inputStream.use { input -> apk.outputStream().use { out ->
                    val buf=ByteArray(65536)
                    while(true) {
                        val n=input.read(buf); if(n<0) break
                        done+=n; require(done<=size && done<=MAX_APK)
                        digest.update(buf,0,n); out.write(buf,0,n)
                        UpdateStatus.text="Downloading update... ${done*100/size}%"
                    }
                } } } finally { download.disconnect() }
                val actual=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(done==size && actual==hash) { "Download verification failed" }
                @Suppress("DEPRECATION")
                val info=app.packageManager.getPackageArchiveInfo(apk.path,0) ?: error("Invalid APK")
                @Suppress("DEPRECATION")
                val version=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
                require(info.packageName==app.packageName && version==newest && version>BuildConfig.VERSION_CODE)
                UpdateStatus.text="Opening Android's update confirmation..."
                install(apk)
                handedToInstaller=true
            } catch(_:Exception) { UpdateStatus.text="Couldn't update. Check internet and tap Update to retry." }
            finally { apk.delete(); if(!handedToInstaller) UpdateStatus.busy.set(false) }
        }
    }
    private fun connection(path:String):HttpURLConnection {
        var url=URL("$path?t=${System.currentTimeMillis()}")
        repeat(6) {
            require(url.protocol=="https")
            val c=url.openConnection() as HttpURLConnection
            c.connectTimeout=15000; c.readTimeout=30000; c.instanceFollowRedirects=false
            c.setRequestProperty("User-Agent","TeamUpOpenWorld-Updater")
            try {
                val code=c.responseCode
                if(code in 300..399) { url=URL(url,c.getHeaderField("Location") ?: error("Missing redirect")); c.disconnect() }
                else { require(code==200); return c }
            } catch(e:Exception) { c.disconnect(); throw e }
        }
        error("Too many redirects")
    }
    private fun install(apk:File) {
        val installer=app.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id=installer.createSession(params)
        try { installer.openSession(id).use { session ->
            session.openWrite("base.apk",0,apk.length()).use { output -> apk.inputStream().use { it.copyTo(output) }; session.fsync(output) }
            val callback=Intent(app,UpdateReceiver::class.java).setAction(app.packageName+".UPDATE_RESULT")
            val flags=PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app,id,callback,flags).intentSender)
        } } catch(e:Exception) { installer.abandonSession(id); throw e }
    }
    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(1024)
        while(true) { val n=read(buffer); if(n<0) break; require(output.size()+n<=limit); output.write(buffer,0,n) }
        return output.toByteArray()
    }
    companion object {
        private const val BASE="https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/openworld-latest/"
        private const val MAX_APK=128L*1024*1024
    }
}
