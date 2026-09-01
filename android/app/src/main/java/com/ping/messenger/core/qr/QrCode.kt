package com.ping.messenger.core.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QR generation and decoding, using ZXing's core library directly.
 *
 * Deliberately not ML Kit or an embedded scanner activity: generating a QR is a pure function
 * over a short string, and decoding is a pure function over a camera frame. Both fit in a few
 * dozen lines against `zxing:core`, which keeps the APK smaller and adds no Play Services
 * dependency — worth caring about for an app that wants to be installable from an APK.
 */
object QrCodes {

    /**
     * Renders [content] as a QR bitmap.
     *
     * Error-correction level M tolerates roughly 15% damage, which is the right trade for a
     * code shown on a screen: high enough to survive a poor camera and a bit of glare, low
     * enough to keep the modules large and quick to acquire.
     */
    fun encode(content: String, sizePx: Int = 720): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )

        val width = matrix.width
        val height = matrix.height
        // A single IntArray plus one setPixels call, rather than width*height setPixel calls,
        // which is roughly two orders of magnitude faster at this size.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()

    /**
     * Decodes a QR from a camera luminance plane.
     *
     * Takes the raw Y plane from CameraX's ImageAnalysis rather than a Bitmap, so scanning
     * never allocates a full-colour frame — that is what makes continuous scanning cheap
     * enough to run on every frame.
     */
    fun decodeLuminance(
        data: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
    ): String? = runCatching {
        val rotated = when (rotationDegrees) {
            90, 270 -> rotate90(data, width, height)
            else -> data
        }
        val (w, h) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            height to width
        } else {
            width to height
        }

        val source = PlanarYUVLuminanceSource(rotated, w, h, 0, 0, w, h, false)
        val binary = com.google.zxing.BinaryBitmap(HybridBinarizer(source))
        com.google.zxing.MultiFormatReader().apply {
            setHints(
                mapOf(
                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    com.google.zxing.DecodeHintType.TRY_HARDER to true,
                ),
            )
        }.decodeWithState(binary).text
    }.getOrNull()

    private fun rotate90(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        var index = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                out[index++] = data[y * width + x]
            }
        }
        return out
    }

    /** True when [payload] is a Ping contact link this app knows how to open. */
    fun isPingContactLink(payload: String): Boolean =
        payload.startsWith("ping://user/") || payload.startsWith("@")

    fun contactLinkFor(username: String): String = "ping://user/$username"
}

/** Generates a QR off the main thread and recomposes when it is ready. */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 720): Bitmap? {
    var bitmap by remember(content, sizePx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content, sizePx) {
        bitmap = withContext(Dispatchers.Default) { QrCodes.encode(content, sizePx) }
    }
    return bitmap
}
