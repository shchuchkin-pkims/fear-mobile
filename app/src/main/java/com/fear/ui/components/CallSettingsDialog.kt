package com.fear.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors
import kotlin.math.roundToInt

/**
 * Настройки звонков: микрофон и качество видео.
 *
 * Зачем руками, если есть автоматика. Микрофоны у телефонов разные, и
 * разница между гарнитурой у рта и микрофоном в чехле - добрый десяток
 * децибел; системная автоматика выравнивает это не всегда. Подавление шума
 * здесь системное: на телефоне оно часто сделано прямо в звуковом тракте
 * устройства и лучше всего, что можно написать самому, - но на некоторых
 * аппаратах ощутимо режет тихую речь, и человеку виднее, что для него хуже.
 *
 * Качество видео тоже вручную: готовые наборы покрывают обычные случаи, но
 * не мобильный интернет в дороге и не гигабитный вайфай дома.
 */
@Composable
fun CallSettingsDialog(ctx: Context, onDismiss: () -> Unit) {
    val colors = LocalFearColors.current
    val prefs = remember {
        ctx.getSharedPreferences("fear_prefs", Context.MODE_PRIVATE)
    }

    var gainDb by remember {
        mutableFloatStateOf(prefs.getInt("audio_mic_gain_db", 0).toFloat())
    }
    var suppress by remember {
        mutableStateOf(prefs.getBoolean("audio_noise_suppress", true))
    }
    var videoKbps by remember {
        mutableFloatStateOf(prefs.getInt("video_bitrate_kbps", 800).toFloat())
    }
    var videoFps by remember {
        mutableFloatStateOf(prefs.getInt("video_fps", 25).toFloat())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        title = { Text("Call settings", color = colors.textPrimary) },
        text = {
            Column {
                Text("Microphone", color = colors.textPrimary,
                     fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", color = colors.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        (if (gainDb > 0) "+" else "") + gainDb.roundToInt() + " dB",
                        color = colors.textPrimary, fontSize = 13.sp,
                    )
                }
                Slider(
                    value = gainDb,
                    onValueChange = { gainDb = it },
                    valueRange = -24f..24f,
                    steps = 47,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Noise suppression", color = colors.textSecondary,
                         fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = suppress, onCheckedChange = { suppress = it })
                }
                Text(
                    "Uses the phone's own suppression. On some devices it trims " +
                        "quiet speech - turn it off if people stop hearing you.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )

                Spacer(Modifier.height(14.dp))
                Text("Video", color = colors.textPrimary,
                     fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                Text("Bitrate: ${videoKbps.roundToInt()} kbit/s",
                     color = colors.textSecondary, fontSize = 13.sp)
                Slider(
                    value = videoKbps,
                    onValueChange = { videoKbps = it },
                    valueRange = 128f..4000f,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Frame rate: ${videoFps.roundToInt()} fps",
                     color = colors.textSecondary, fontSize = 13.sp)
                Slider(
                    value = videoFps,
                    onValueChange = { videoFps = it },
                    valueRange = 10f..30f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lower both on mobile data: a call that keeps up is worth " +
                        "more than a sharp one that stalls.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putInt("audio_mic_gain_db", gainDb.roundToInt())
                    .putBoolean("audio_noise_suppress", suppress)
                    .putInt("video_bitrate_kbps", videoKbps.roundToInt())
                    .putInt("video_fps", videoFps.roundToInt())
                    .apply()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
