package com.hermexapp.android.features.workspace

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Largest edge, in pixels, a preview is decoded to.
 *
 * The browser shows workspace output, which includes renders and screenshots
 * far larger than any phone screen. A full-size decode of one of those is tens
 * of megabytes of bitmap for an image that will be drawn at a fraction of the
 * size, so previews are downsampled on the way in rather than after the
 * allocation that would have hurt.
 */
private const val MAX_PREVIEW_EDGE_PX = 2048

/**
 * Decodes [bytes] to a bitmap no larger than [MAX_PREVIEW_EDGE_PX] on its long
 * edge, or null when the bytes are not a decodable image.
 *
 * Bounds are measured first (`inJustDecodeBounds`), then `inSampleSize` picks a
 * power-of-two reduction — the only value `BitmapFactory` honours.
 *
 * A null return is a real outcome, not only a corrupt file: the extension
 * classifier works off the name, so anything named `.png` reaches here whatever
 * its contents, and a mislabelled file must read as "cannot preview" rather
 * than crash the screen.
 */
internal fun decodeSampledImage(bytes: ByteArray): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (
        bounds.outWidth / sample > MAX_PREVIEW_EDGE_PX ||
        bounds.outHeight / sample > MAX_PREVIEW_EDGE_PX
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

/**
 * Shows the image at [absolutePath], fetched through `/api/media`.
 *
 * Keyed on the path so reopening a different image re-fetches rather than
 * showing the previous one while the new bytes arrive.
 *
 * Every failure lands as a message rather than an empty frame. The whole point
 * of this screen is that a tap on an image used to produce mojibake with no
 * explanation; silence would be the same defect with different pixels.
 */
@Composable
fun MediaPreview(
    absolutePath: String,
    loadBytes: suspend (String) -> ByteArray,
    modifier: Modifier = Modifier,
) {
    var image by remember(absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(absolutePath) { mutableStateOf<String?>(null) }
    val palette = LocalHermexPalette.current

    LaunchedEffect(absolutePath) {
        image = null
        failure = null
        val bytes = runCatching { loadBytes(absolutePath) }
        val decoded = bytes.getOrNull()?.let { decodeSampledImage(it) }
        when {
            // The server's own reason where there is one — "too large" and
            // "unreachable" call for different responses from the reader, and
            // one message covering both tells them nothing.
            bytes.isFailure -> failure = (bytes.exceptionOrNull() as? ApiError)?.userMessage
                ?: "Could not load this image from the server."
            decoded == null -> failure = "This file is named like an image but could not be decoded."
            else -> image = decoded.asImageBitmap()
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = image
        val message = failure
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = "Preview of ${absolutePath.substringAfterLast('/')
                    .substringAfterLast('\\')}",
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit,
            )

            message != null -> Text(
                message,
                color = palette.destructive,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            else -> CircularProgressIndicator()
        }
    }
}
