package com.fear.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders `text` as a QR code.
 *
 * The Image is forced square via `aspectRatio(1f)` so it always fills the
 * available width while remaining a true 1:1 QR. We render a 1024x1024
 * bitmap so the QR stays sharp even on tablets — Compose will scale down
 * for layout. `FilterQuality.None` preserves crisp module edges (no
 * bilinear smoothing turning black squares into grey blobs).
 */
@Composable
fun QrCodeView(
    text: String,
    modifier: Modifier = Modifier,
    pixelsPerSide: Int = 1024,
) {
    val bitmap = remember(text, pixelsPerSide) {
        encodeQrBitmap(text, pixelsPerSide)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code",
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
    )
}

private fun encodeQrBitmap(text: String, sidePx: Int): Bitmap {
    val writer = QRCodeWriter()
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = writer.encode(text, BarcodeFormat.QR_CODE, sidePx, sidePx, hints)
    val bmp = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
    for (y in 0 until sidePx) {
        for (x in 0 until sidePx) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

/** Same as encodeQrBitmap but exposed for callers that want to save the PNG. */
fun renderQrBitmap(text: String, pixelsPerSide: Int = 1024): Bitmap =
    encodeQrBitmap(text, pixelsPerSide)
