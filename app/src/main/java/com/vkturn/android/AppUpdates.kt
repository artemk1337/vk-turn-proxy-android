package com.vkturn.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal data class AppRelease(val version: String, val url: String, val digest: String, val size: Long)

internal object UpdateVersion {
    fun parse(value: String): List<Int>? {
        val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    fun newer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (index in next.indices) {
            if (next[index] != installed[index]) return next[index] > installed[index]
        }
        return false
    }
}

internal object AppUpdates {
    private const val API = "https://api.github.com/repos/artemk1337/vk-turn-proxy-android/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/artemk1337/vk-turn-proxy-android/releases/download/"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var busy = false
        private set
    @Volatile var downloaded = 0L
        private set
    @Volatile var release: AppRelease? = null
        private set
    @Volatile var failure = false
        private set

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    @Synchronized fun prepare(context: Context) {
        if (busy) return
        val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        if (preferences.getString("downloadedFor", "") != installedVersion(context)) {
            apk(context).delete()
            File(apk(context).parentFile, "digest").delete()
        }
    }

    fun check(context: Context, automatic: Boolean = false, complete: (Result<AppRelease?>) -> Unit = {}) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        synchronized(this) {
            if (busy) return
            val elapsed = System.currentTimeMillis() - preferences.getLong("lastCheck", 0)
            if (automatic && elapsed in 0 until 86_400_000L) return
            busy = true
            downloaded = 0
            failure = false
        }
        worker.execute {
            val result = runCatching {
                val connection = URL(API).openConnection() as javax.net.ssl.HttpsURLConnection
                val data = try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "VK-TURN-Proxy-Android")
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size <= 1024 * 1024)
                    JSONObject(String(bytes, Charsets.UTF_8))
                } finally { connection.disconnect() }
                require(!data.getBoolean("draft") && !data.getBoolean("prerelease"))
                val version = data.getString("tag_name")
                require(UpdateVersion.parse(version) != null)
                val available = if (UpdateVersion.newer(version, installedVersion(app))) {
                    val assets = data.getJSONArray("assets")
                    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .single { it.getString("name") == "vk-turn-proxy-android-$version.apk" }
                    val url = asset.getString("browser_download_url")
                    val digest = asset.getString("digest").removePrefix("sha256:")
                    val size = asset.getLong("size")
                    require(url == "$DOWNLOAD_PREFIX$version/vk-turn-proxy-android-$version.apk")
                    require(digest.matches(Regex("[a-f0-9]{64}")) && size in 1..MAX_APK_BYTES)
                    AppRelease(version, url, digest, size)
                } else null
                val storedDigest = File(apk(app).parentFile, "digest")
                if (apk(app).exists() && (available == null || runCatching { storedDigest.readText() }.getOrNull() != available.digest)) {
                    apk(app).delete()
                    storedDigest.delete()
                }
                release = available
                preferences.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                available
            }
            failure = result.isFailure
            busy = false
            Handler(Looper.getMainLooper()).post { complete(result) }
        }
    }

    fun apk(context: Context): File = File(context.noBackupFilesDir, "updates/update.apk")

    fun download(context: Context, selected: AppRelease) {
        synchronized(this) {
            if (busy) return
            busy = true
            failure = false
            downloaded = 0
        }
        val app = context.applicationContext
        worker.execute {
            val target = apk(app)
            val partial = File(target.parentFile, "update.part")
            val result = runCatching {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                target.delete()
                File(target.parentFile, "digest").delete()
                val connection = URL(selected.url).openConnection() as javax.net.ssl.HttpsURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    require(connection.responseCode == 200)
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                require(downloaded <= selected.size)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                require(downloaded == selected.size)
                verify(app, partial, selected.digest)
                @Suppress("DEPRECATION")
                val archive = app.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)!!
                require(archive.versionName == selected.version.removePrefix("v"))
                check(partial.renameTo(target))
                File(target.parentFile, "digest").writeText(selected.digest)
                check(app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
                    .putString("downloadedFor", installedVersion(app)).commit())
            }
            partial.delete()
            if (result.isFailure) target.delete()
            failure = result.isFailure
            busy = false
        }
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File = apk(context), expectedDigest: String = File(file.parentFile, "digest").readText()) {
        require(expectedDigest.matches(Regex("[a-f0-9]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest)
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: error("Invalid APK")
        require(archive.packageName == context.packageName)
        require(archive.versionCode > installed.versionCode)
        require(UpdateVersion.newer(archive.versionName.orEmpty(), installed.versionName.orEmpty()))
        val currentSigners = installed.signatures.orEmpty().toSet()
        require(currentSigners.isNotEmpty() && archive.signatures.orEmpty().toSet() == currentSigners)
    }
}
