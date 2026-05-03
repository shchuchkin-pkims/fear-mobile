package com.fear.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders `text` as a QR code Bitmap inside a Composable Image.
 *
 * The produced QR uses error-correction level M and no margin: any quiet zone
 * the user wants comes from `Modifier.padding(...)`. We render at a fixed
 * `pixelsPerSide` (default 512) — Compose will scale down to layout size, so
 * detail loss only happens if the dialog is huge on a tablet.
 */
@Composable
fun QrCodeView(
    text: String,
    modifier: Modifier = Modifier,
    pixelsPerSide: Int = 512,
) {
    val bitmap = remember(text, pixelsPerSide) {
        encodeQrBitmap(text, pixelsPerSide)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code",
        modifier = modifier
            .fillMaxWidth()
            .height(with(androidx.compose.ui.platform.LocalDensity.current) {
                pixelsPerSide.toDp()
            })
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(12.dp),
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
