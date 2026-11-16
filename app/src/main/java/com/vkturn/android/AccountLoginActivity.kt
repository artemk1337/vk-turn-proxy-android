package com.vkturn.android

import android.webkit.CookieManager
import android.content.Intent
import com.vkturn.backend.mobile.Mobile
import java.util.concurrent.Executors

internal class AccountLoginActivity : AccountBrowserActivity() {
    override val browserDirectory = "vk-login"
    override val titleResource = R.string.account_login_wait
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBrowserReady() {
        addButton(R.string.account_login_done) { saveAccount() }
        browser.loadUrl("https://vk.ru/")
    }

    private fun saveAccount() {
        val manager = CookieManager.getInstance()
        val domains = listOf("https://vk.ru/", "https://vk.com/", "https://login.vk.ru/", "https://login.vk.com/")
        val values = mutableMapOf<String, String>()
        for (domain in domains) {
            manager.getCookie(domain).orEmpty().split(';').forEach {
                val pair = it.trim().split('=', limit = 2)
                if (pair.size == 2 && pair[0] in listOf("remixsid", "p") && pair[1].isNotEmpty()) values[pair[0]] = pair[1]
            }
        }
        if (values.keys != setOf("remixsid", "p")) {
            status.setText(R.string.account_login_error)
            return
        }
        val header = "remixsid=${values.getValue("remixsid")}; p=${values.getValue("p")}"
        worker.execute {
            val saved = runCatching {
                require(header.length <= 64 * 1024)
                Mobile.normalizeCookies(header)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                saved.onSuccess {
                    setResult(RESULT_OK, Intent().putExtra(AccountScreen.EXTRA_COOKIES, it))
                    finish()
                }.onFailure { status.setText(R.string.account_login_error) }
            }
        }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
