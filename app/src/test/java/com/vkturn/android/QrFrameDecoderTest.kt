package com.vkturn.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {
    @Test fun readsConnectionLinksWithoutChangingTheirContent() {
        val value = "vkturnproxy://import?data=a+b/c=="
        val qr = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256)
        val frame = ByteArray(256 * 256) { index -> if (qr[index % 256, index / 256]) 0 else 255.toByte() }
        assertEquals(value, decodeQrFrame(frame, 256, 256))
    }

    @Test fun readsRotatedQrContainingUnicode() {
        val value = "{\"name\":\"\u041b\u0438\u0447\u043d\u044b\u0439 VPN\"}"
        val qr = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val rotated = ByteArray(256 * 256) { index -> if (qr[index / 256, 255 - index % 256]) 0 else 255.toByte() }
        assertEquals(value, decodeQrFrame(rotated, 256, 256))
    }

    @Test fun ignoresFramesWithoutQrCodes() {
        assertNull(decodeQrFrame(ByteArray(256 * 256) { 255.toByte() }, 256, 256))
    }
}
