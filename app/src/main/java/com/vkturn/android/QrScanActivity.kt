package com.vkturn.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class QrScanActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surface: SurfaceView
    private lateinit var preview: FrameLayout
    private var camera: Camera? = null
    private var resumed = false
    private var surfaceReady = false
    private var permissionRequested = false
    private var permissionPending = false
    private var permissionDialog: AlertDialog? = null
    private var cameraFailed = false
    private val decoding = AtomicBoolean(false)
    private val decoder = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(base: Context) { super.attachBaseContext(localizedAppContext(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        permissionPending = savedInstanceState?.getBoolean("permissionPending") ?: false
        val ui = ProfileUi(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.background) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(16), ui.dp(8)) }
        toolbar.addView(ui.button(R.string.cancel) { finish() })
        toolbar.addView(TextView(this).apply { setText(R.string.qr_scan_title); textSize = 20f; setTextColor(ui.ink) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(12) })
        root.addView(toolbar)
        preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { holder.addCallback(this@QrScanActivity) }
        preview.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            setText(R.string.qr_scan_hint); textSize = 15f; setTextColor(ui.ink); gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20))
        })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (!permissionRequested) {
            permissionRequested = true
            permissionPending = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else if (!permissionPending) showPermissionRequired()
    }

    override fun onPause() { resumed = false; stopCamera(); super.onPause() }
    override fun onDestroy() { permissionDialog?.dismiss(); decoder.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("permissionRequested", permissionRequested); outState.putBoolean("permissionPending", permissionPending); super.onSaveInstanceState(outState) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION) return
        permissionPending = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else showPermissionRequired()
    }

    private fun showPermissionRequired() {
        if (!resumed || isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
        permissionDialog = AlertDialog.Builder(this).setMessage(R.string.qr_camera_permission)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.setOnCancelListener { finish() }.show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { startCamera() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopCamera() }

    private fun startCamera() {
        if (!resumed || !surfaceReady || camera != null || cameraFailed || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val result = runCatching {
            check(Camera.getNumberOfCameras() > 0)
            val cameraId = (0 until Camera.getNumberOfCameras()).firstOrNull {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(it, info)
                info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
            val opened = Camera.open(cameraId)
            camera = opened
            val parameters = opened.parameters
            val sizes = parameters.supportedPreviewSizes
            val size = sizes.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
                ?: sizes.minBy { it.width * it.height }
            parameters.setPreviewSize(size.width, size.height)
            parameters.previewFormat = android.graphics.ImageFormat.NV21
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in parameters.supportedFocusModes.orEmpty()) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            opened.parameters = parameters
            val degrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val orientation = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + degrees) % 360) % 360
                else (info.orientation - degrees + 360) % 360
            opened.setDisplayOrientation(orientation)
            preview.post {
                val width = if (orientation % 180 == 0) size.width else size.height
                val height = if (orientation % 180 == 0) size.height else size.width
                val scale = maxOf(preview.width.toFloat() / width, preview.height.toFloat() / height)
                surface.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
            }
            opened.setPreviewDisplay(surface.holder)
            opened.setPreviewCallbackWithBuffer { data, source -> decode(data, source, size.width, size.height) }
            opened.addCallbackBuffer(ByteArray(size.width * size.height * 3 / 2))
            opened.startPreview()
        }
        if (result.isFailure) {
            cameraFailed = true
            stopCamera()
            AlertDialog.Builder(this).setMessage(R.string.qr_camera_failed).setPositiveButton(R.string.close) { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
        }
    }

    private fun decode(data: ByteArray, source: Camera, width: Int, height: Int) {
        if (!resumed || camera !== source) return
        if (!decoding.compareAndSet(false, true)) { source.addCallbackBuffer(data); return }
        decoder.execute {
            val content = decodeQrFrame(data, width, height)
            decoding.set(false)
            runOnUiThread {
                if (!resumed || camera !== source || isFinishing || isDestroyed) return@runOnUiThread
                if (content != null) {
                    stopCamera()
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CONTENT, content))
                    finish()
                } else source.addCallbackBuffer(data)
            }
        }
    }

    private fun stopCamera() {
        val opened = camera ?: return
        camera = null
        runCatching { opened.setPreviewCallbackWithBuffer(null) }
        runCatching { opened.stopPreview() }
        runCatching { opened.release() }
    }

    companion object {
        const val EXTRA_CONTENT = "qrContent"
        private const val CAMERA_PERMISSION = 51
    }
}

internal fun decodeQrFrame(data: ByteArray, width: Int, height: Int): String? = runCatching {
    val image = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image)))
    result.text.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES }
}.getOrNull()
