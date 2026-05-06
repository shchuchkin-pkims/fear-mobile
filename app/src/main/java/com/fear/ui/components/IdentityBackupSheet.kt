package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

/**
 * Вспомогательное окно для всех действий с криптографической идентичностью:
 * экспорт и импорт зашифрованного файла резервной копии (`.fbk`), показ
 * идентичности в виде QR-кода и импорт идентичности из QR-кода. Раньше эти
 * четыре действия занимали отдельные строки в основном меню; теперь они
 * вынесены сюда, чтобы основное меню оставалось коротким.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityBackupSheet(
    onDismiss: () -> Unit,
    onExportIdentity: () -> Unit,
    onImportIdentity: () -> Unit,
    onShowQr: () -> Unit,
    onImportQr: () -> Unit,
) {
    val colors = LocalFearColors.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "Идентичность и резервное копирование",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ItemRow(
                Icons.Filled.Backup,
                "Экспортировать в файл (.fbk)",
                "Сохранить зашифрованную паролем резервную копию ключей.",
            ) { onExportIdentity(); onDismiss() }
            ItemRow(
                Icons.Filled.Restore,
                "Импортировать из файла (.fbk)",
                "Восстановить идентичность из ранее сохранённого файла.",
            ) { onImportIdentity(); onDismiss() }
            ItemRow(
                Icons.Filled.QrCode,
                "Показать как QR-код",
                "Перенести идентичность на другое устройство сканированием.",
            ) { onShowQr(); onDismiss() }
            ItemRow(
                Icons.Filled.QrCodeScanner,
                "Импортировать из QR-кода",
                "Восстановить идентичность с QR-кода (камера или изображение).",
            ) { onImportQr(); onDismiss() }
        }
    }
}

@Composable
private fun ItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary,
             modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 15.sp,
                 fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp,
                 modifier = Modifier.padding(top = 2.dp))
        }
    }
}
