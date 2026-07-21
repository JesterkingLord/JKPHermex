package com.hermexapp.android.features.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [QrDecoder]'s pure decode seam ([QrDecoder.decodeArgb]).
 *
 * These run on a plain JVM (no Robolectric, no camera, no [android.graphics.Bitmap]):
 * ZXing's [QRCodeWriter] is pure Java, so we encode a symbol to a pixel array,
 * run it back through the exact seam the live camera scanner uses, and assert
 * the round-trip. This pins decode behaviour without needing hardware.
 */
class QrDecoderTest {

    /**
     * Render a QR [text] to a greyscale ARGB pixel array with a quiet-zone
     * margin, mirroring how [QrDecoder.yLuminanceFromYuv] lays out opaque
     * greyscale pixels. [quietZone] modules of white padding are added on
     * every side (QR readers require ≥4 modules of quiet zone).
     */
    private fun renderQrArgb(text: String, quietZone: Int = 8): Triple<IntArray, Int, Int> {
        val writer = QRCodeWriter()
        // ZXing's writer defaults to ISO-8859-1; pair it with the decoder's
        // UTF-8 hint so multi-byte (unicode) payloads survive the round-trip.
        val hints = mapOf(
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width + quietZone * 2
        val h = matrix.height + quietZone * 2
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val mx = x - quietZone
                val my = y - quietZone
                val dark = mx in 0 until matrix.width && my in 0 until matrix.height &&
                    matrix.get(mx, my)
                argb[y * w + x] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Triple(argb, w, h)
    }

    @Test
    fun `round-trips a realistic pairing URL`() {
        val url = "https://hermes.example.com/v1/pair/connect?pair_id=abc123&token=***"
        val (argb, w, h) = renderQrArgb(url)
        assertEquals(url, QrDecoder.decodeArgb(argb, w, h))
    }

    @Test
    fun `round-trips a tailscale pairing URL`() {
        val url = "http://100.64.1.2:8787/v1/pair/connect?pair_id=zz9&token=***"
        val (argb, w, h) = renderQrArgb(url)
        assertEquals(url, QrDecoder.decodeArgb(argb, w, h))
    }

    @Test
    fun `round-trips a short payload`() {
        val (argb, w, h) = renderQrArgb("JKP")
        assertEquals("JKP", QrDecoder.decodeArgb(argb, w, h))
    }

    @Test
    fun `round-trips unicode content`() {
        val payload = "pair://JKP?name=موبايل&note=🎉"
        val (argb, w, h) = renderQrArgb(payload)
        assertEquals(payload, QrDecoder.decodeArgb(argb, w, h))
    }

    @Test
    fun `returns null for solid-white frame (no symbol)`() {
        val w = 200
        val h = 200
        val white = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertNull(QrDecoder.decodeArgb(white, w, h))
    }

    @Test
    fun `returns null for random noise (no decodable symbol)`() {
        val w = 120
        val h = 120
        val rng = java.util.Random(42)
        val noise = IntArray(w * h) {
            if (rng.nextBoolean()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertNull(QrDecoder.decodeArgb(noise, w, h))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(QrDecoder.decodeArgb(IntArray(0), 0, 0))
    }

    @Test
    fun `does not decode a non-QR barcode (QR-only hints)`() {
        // Encode a Code-128 barcode; the decoder is restricted to QR_CODE via
        // POSSIBLE_FORMATS, so this must not be picked up as a QR symbol.
        val writer = com.google.zxing.oned.Code128Writer()
        val matrix = writer.encode("12345", BarcodeFormat.CODE_128, 200, 50)
        val w = matrix.width
        val h = matrix.height
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                argb[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        assertNull(QrDecoder.decodeArgb(argb, w, h))
    }
}
