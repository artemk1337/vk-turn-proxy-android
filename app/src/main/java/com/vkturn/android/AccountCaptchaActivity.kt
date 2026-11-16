package com.vkturn.android

import android.webkit.JavascriptInterface
import android.net.Uri
import android.widget.EditText
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class AccountCaptchaActivity : AccountBrowserActivity() {
    override val browserDirectory = "vk-captcha"
    override val titleResource = R.string.account_captcha
    private val worker = Executors.newSingleThreadExecutor()
    private val submitted = AtomicBoolean(false)

    override fun onBrowserReady() {
        browser.addJavascriptInterface(CaptchaBridge(), "VKCaptcha")
        val answer = EditText(this).apply {
            setHint(R.string.account_captcha_answer)
            setSingleLine()
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        controls.addView(answer, 0)
        addButton(R.string.account_captcha_submit) { submit(answer.text.toString()) }
        addButton(R.string.account_captcha_refresh) { loadChallenge(true) }
        loadChallenge(false)
    }

    private fun loadChallenge(refresh: Boolean) {
        worker.execute {
            val challenge = runCatching {
                JSONObject(if (refresh) TunnelService.refreshCaptcha() else TunnelService.captcha())
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val url = challenge?.optString("url").orEmpty()
                if (challenge?.optBoolean("pending") == true && isAllowedPage(url)) {
                    browser.loadUrl(url)
                } else {
                    status.setText(R.string.account_captcha_missing)
                }
            }
        }
    }

    private fun submit(answer: String) {
        if (answer.isBlank() || !submitted.compareAndSet(false, true)) return
        worker.execute {
            val result = runCatching { TunnelService.submitCaptcha(answer) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { finish() }.onFailure {
                    submitted.set(false)
                    status.setText(R.string.account_captcha_error)
                }
            }
        }
    }

    override fun onTrustedPageLoaded() {
        // Observe the widget's own result without issuing a second captcha request.
        browser.evaluateJavascript(CAPTCHA_OBSERVER, null)
    }

    override fun interceptNavigation(raw: String): Boolean {
        if (!isAllowedPage(browser.url.orEmpty())) return false
        val uri = Uri.parse(raw)
        val answer = runCatching {
            uri.getQueryParameter("success_token")
                ?: Uri.parse("https://vk.ru/?${uri.encodedFragment.orEmpty()}").getQueryParameter("success_token")
        }.getOrNull().orEmpty()
        if (answer.isBlank()) return false
        submit(answer)
        return true
    }

    private inner class CaptchaBridge {
        @JavascriptInterface
        fun solved(token: String) {
            if (token.length in 1..16384 && !token.contains('\n') && !token.contains('\r')) {
                runOnUiThread { if (isAllowedPage(browser.url.orEmpty())) submit(token) }
            }
        }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        private val CAPTCHA_OBSERVER = """
            (function() {
              if (window.__vkTurnCaptchaObserver) return;
              window.__vkTurnCaptchaObserver = true;
              function inspect(value) {
                try {
                  if (typeof value === 'string') value = JSON.parse(value);
                  var result = value && (value.response || value.data || value);
                  var token = result && result.success_token;
                  if (typeof token === 'string' && token.length) VKCaptcha.solved(token);
                } catch (_) {}
              }
              window.addEventListener('message', function(event) {
                try {
                  var host = new URL(event.origin).hostname;
                  if (/(^|\.)vk\.(com|ru)$/.test(host)) inspect(event.data);
                } catch (_) {}
              });
              var originalFetch = window.fetch;
              if (originalFetch) window.fetch = function() {
                return originalFetch.apply(this, arguments).then(function(response) {
                  if (response.url.indexOf('captchaNotRobot.check') !== -1)
                    response.clone().text().then(inspect).catch(function() {});
                  return response;
                });
              };
              var originalOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                if (String(url).indexOf('captchaNotRobot.check') !== -1)
                  this.addEventListener('load', function() { inspect(this.responseText); });
                return originalOpen.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
