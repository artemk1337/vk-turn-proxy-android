package com.vkturn.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

internal object AccountScreen {
    private val worker = Executors.newSingleThreadExecutor()
    private const val VK_LOGIN_REQUEST = 41
    private const val VK_CAPTCHA_REQUEST = 42
    const val EXTRA_COOKIES = "vkCookies"
    private var browserOpen = false

    fun show(activity: Activity) {
        val store = AccountStore(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val status = TextView(activity).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(activity).apply { setText(label); setOnClickListener { action() } })
        }
        button(R.string.account_login) { openBrowser(activity, AccountLoginActivity::class.java, VK_LOGIN_REQUEST) }
        button(R.string.account_import) { importCookies(activity, store) }
        button(R.string.account_check) { store.checkNow() }
        button(R.string.account_logout) {
            AlertDialog.Builder(activity).setMessage(R.string.account_logout_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.account_logout) { _, _ -> store.logout() }.show()
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.account_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null).create()
        val handler = Handler(Looper.getMainLooper())
        var revision = store.revision()
        val update = object : Runnable {
            override fun run() {
                val latest = store.revision()
                if (latest != revision) {
                    revision = latest
                    store.checkNow()
                }
                status.setText(when (store.status()) {
                    "valid" -> R.string.account_valid
                    "rejected" -> R.string.account_rejected
                    "unavailable" -> R.string.account_unavailable
                    "signed_out" -> R.string.account_signed_out
                    else -> R.string.account_unknown
                })
                handler.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(update) }
        dialog.show()
        store.checkNow()
        handler.post(update)
    }

    fun showCaptcha(activity: Activity) {
        openBrowser(activity, AccountCaptchaActivity::class.java, VK_CAPTCHA_REQUEST)
    }

    private fun openBrowser(activity: Activity, type: Class<out Activity>, request: Int) {
        if (browserOpen) {
            Toast.makeText(activity, R.string.account_browser_active, Toast.LENGTH_SHORT).show()
            return
        }
        browserOpen = true
        activity.startActivityForResult(Intent(activity, type), request)
    }

    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != VK_LOGIN_REQUEST && requestCode != VK_CAPTCHA_REQUEST) return false
        browserOpen = false
        if (requestCode == VK_CAPTCHA_REQUEST) return true
        if (resultCode == Activity.RESULT_OK) {
            val cookies = data?.getStringExtra(EXTRA_COOKIES).orEmpty()
            data?.removeExtra(EXTRA_COOKIES)
            worker.execute {
                val result = runCatching { AccountStore(activity).importCookies(cookies) }
                activity.runOnUiThread {
                    if (!activity.isDestroyed && result.isFailure) {
                        AlertDialog.Builder(activity).setMessage(R.string.account_login_error)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }
        return true
    }

    private fun importCookies(activity: Activity, store: AccountStore) {
        val input = EditText(activity).apply {
            setHint(R.string.account_cookie_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setHorizontallyScrolling(false)
            minLines = 4
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.account_import).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.account_import, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                worker.execute {
                    val result = runCatching { store.importCookies(text) }
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        result.onSuccess { input.text.clear(); dialog.dismiss() }.onFailure {
                            input.error = activity.getString(R.string.account_cookie_error)
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener { input.text.clear() }
        dialog.show()
    }
}
