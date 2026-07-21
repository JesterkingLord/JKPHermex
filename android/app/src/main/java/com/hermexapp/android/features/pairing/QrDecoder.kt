package com.hermexapp.android.features.pairing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Local-only QR decoder for the 6.6 pairing scanner.
 *
 * Every frame the camera produces is decoded **on-device** with ZXing and
 * discarded immediately — nothing is recorded, cached, or transmitted. The
 * two public entry points cover the two luminance paths we feed it:
 *
 *  - [decodeImageProxy] — CameraX `ImageAnalysis` YUV_420_888 frames (the
 *    live scanner hot path). Only the Y (luminance) plane is used, which is
 *    sufficient for a black/white QR symbol and avoids a costly color
 *    conversion.
 *  - [decodeBitmap] — ARGB bitmaps (used by tests and any future
 *    gallery/image-file entry point).
 *
 * Both delegate to the shared [decode] seam so there is exactly one place
 * that configures the ZXing reader.
 */
object QrDecoder {

    /**
     * QR-only decode hints. Restricting to [BarcodeFormat.QR_CODE] and
     * enabling [DecodeHintType.TRY_HARDER] gives the best accuracy/speed
     * trade-off for a single-format pairing code without wasting cycles on
     * formats we never expect.
     */
    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private val reader = QRCodeReader()

    /**
     * Decode a QR symbol out of a CameraX [ImageProxy].
     *
     * Returns the decoded text, or `null` when the frame carries no readable
     * QR code or is in an unsupported pixel format (we only know how to read
     * the YUV_420_888 stream CameraX produces). The proxy is always closed
     * before returning so CameraX can reuse the buffer.
     */
    fun decodeImageProxy(image: ImageProxy): String? {
        return try {
            decodeArgb(yLuminanceFromYuv(image), image.width, image.height)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    /**
     * The single shared decode seam: run the configured [reader] over an
     * ARGB pixel array (row-major, opaque). Every entry point funnels
     * through here, so decode behaviour cannot drift between the YUV and
     * Bitmap paths — and this seam is JVM-testable (no [android.graphics.Bitmap]
     * dependency), which is why the unit tests drive it directly.
     *
     * @return the decoded QR text, or `null` when no symbol is present or the
     *   image is unparseable.
     */
    fun decodeArgb(argb: IntArray, width: Int, height: Int): String? {
        return try {
            val source = RGBLuminanceSource(width, height, argb)
            reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decode a QR symbol out of a [Bitmap] (ARGB_8888). Thin wrapper over
     * [decodeArgb]; exposed so a future file/gallery entry point can reuse
     * the same decode path. Returns the decoded text or `null`.
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        return decodeArgb(argb, width, height)
    }

    /**
     * Build an ARGB luminance array from a YUV_420_888 [image]'s Y plane.
     *
     * The Y plane carries full-resolution luminance (0–255); for a QR symbol
     * that is all we need. We expand each byte to an opaque ARGB pixel so the
     * shared [decode] seam can treat every path uniformly. Rows are copied
     * with the plane's [ImageProxy.PlaneProxy.rowStride] honoured, because
     * CameraX pads rows beyond [image.getWidth] on some devices.
     */
    private fun yLuminanceFromYuv(image: ImageProxy): IntArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            for (col in 0 until width) {
                val luma = buffer.get(rowOffset + col).toInt() and 0xFF
                // Opaque ARGB with R=G=B=luma: a greyscale pixel.
                pixels[row * width + col] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return pixels
    }
}
