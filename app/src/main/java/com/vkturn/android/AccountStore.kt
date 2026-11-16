package com.vkturn.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.vkturn.backend.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AccountStore(context: Context, private val monitor: Boolean = true) {
    private val application = context.applicationContext
    private val account = AtomicFile(File(application.noBackupFilesDir, "vk-account.enc"))

    init {
        synchronized(lock) {
            if (monitor && !checksStarted) {
                checksStarted = true
                val interval = JSONObject(Mobile.defaults()).getLong("vkCheckSeconds")
                checks.scheduleWithFixedDelay({ refresh(application) }, 0, interval, TimeUnit.SECONDS)
            }
        }
    }

    fun readCookies(): String = synchronized(lock) {
        if (!account.baseFile.exists()) return@synchronized ""
        val bytes = account.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= MAX_COOKIE_BYTES)
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        require(bytes.size in 30..MAX_COOKIE_BYTES && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
    }

    fun importCookies(input: String) {
        val normalized = Mobile.normalizeCookies(input)
        synchronized(lock) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
            val output = account.startWrite()
            try {
                output.write(bytes)
                account.finishWrite(output)
            } catch (error: Exception) {
                account.failWrite(output)
                throw error
            }
            generation++
            state = "unknown"
        }
        if (monitor) checkNow()
    }

    fun logout() = synchronized(lock) {
        account.delete()
        generation++
        state = "signed_out"
    }

    fun hasAccount(): Boolean = account.baseFile.exists()

    fun status(): String = if (!hasAccount()) "signed_out" else if (state == "signed_out") "unknown" else state

    fun revision(): Long = account.baseFile.lastModified() xor account.baseFile.length()

    fun checkNow() { checks.execute { refresh(application) } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "vk-turn-proxy-account"
        private const val MAX_COOKIE_BYTES = 4 * 1024 * 1024 + 64
        private val lock = Any()
        private val checks = Executors.newSingleThreadScheduledExecutor()
        private var checksStarted = false
        private var generation = 0L
        @Volatile private var state = "signed_out"

        private fun refresh(context: Context) {
            val store = AccountStore(context, false)
            val snapshot = synchronized(lock) {
                val input = runCatching { store.readCookies() }.getOrElse {
                    state = "unavailable"
                    return
                }
                if (input.isEmpty()) {
                    state = "signed_out"
                    return
                }
                generation to input
            }
            val result = runCatching { Mobile.checkAccount(snapshot.second) }.getOrDefault("unavailable")
            synchronized(lock) {
                if (generation == snapshot.first && runCatching { store.readCookies() == snapshot.second }.getOrDefault(false)) state = result
            }
        }
    }
}
