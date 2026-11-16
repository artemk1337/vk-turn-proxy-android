package com.vkturn.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal abstract class AccountBrowserActivity : Activity() {
    protected lateinit var browser: WebView
    protected lateinit var status: TextView
    protected lateinit var controls: LinearLayout

    protected abstract val browserDirectory: String
    protected abstract val titleResource: Int

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(localizedAppContext(base))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            synchronized(AccountBrowserActivity::class.java) {
                if (!directorySet) {
                    WebView.setDataDirectorySuffix(browserDirectory)
                    directorySet = true
                }
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            setText(titleResource)
            setPadding(20, 20, 20, 20)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        browser = WebView(this).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (interceptNavigation(request.url.toString())) return true
                    if (isAllowedPage(request.url.toString())) return false
                    status.setText(R.string.account_page_blocked)
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isAllowedPage(url)) onTrustedPageLoaded()
                }
            }
        }
        content.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(controls)
        setContentView(content)
        addButton(R.string.account_close) { finish() }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(browser, true)
        cookies.removeAllCookies {
            if (!isFinishing && !isDestroyed) {
                WebStorage.getInstance().deleteAllData()
                onBrowserReady()
            }
        }
    }

    protected fun addButton(label: Int, action: () -> Unit): Button = Button(this).also {
        it.setText(label)
        it.setOnClickListener { action() }
        controls.addView(it)
    }

    protected abstract fun onBrowserReady()
    protected open fun onTrustedPageLoaded() {}
    protected open fun interceptNavigation(raw: String): Boolean = false

    protected fun isAllowedPage(raw: String): Boolean {
        val uri = Uri.parse(raw)
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            listOf("vk.com", "vk.ru", "vk-captcha.ru", "vk-captcha.com", "vkuser.net", "userapi.com").any {
                host == it || host.endsWith(".$it")
            }
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.removeJavascriptInterface("VKCaptcha")
            browser.clearHistory()
            browser.clearCache(true)
            browser.loadUrl("about:blank")
            browser.destroy()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }

    companion object {
        private var directorySet = false
    }
}
