package com.fear.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.R
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.ConnectFormState
import com.fear.ui.viewmodel.ConnectMode
import com.fear.ui.viewmodel.FearViewModel

private data class ServerOption(val label: String, val host: String)
private val ServerPresets = listOf(
    // Меппел первым: это сервер по умолчанию, и первый пункт списка - то,
    // что видит человек, никогда этот список не открывавший.
    ServerOption("77.221.145.132 — Netherlands (Meppel)", "77.221.145.132"),
    ServerOption("fear-project.ru — Russia (Moscow)",     "fear-project.ru"),
)

/**
 * Single-button connect screen.
 *
 * Compositional layout: a fixed logo at the top, a scrollable middle that
 * holds the identity card and connection form, and fixed action buttons at
 * the bottom (Connect, Register). The middle is the only scroll surface so
 * the buttons stay anchored even with the Advanced section expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    form: ConnectFormState,
    displayName: String,
    isConnecting: Boolean,
    errorBanner: String?,
    regState: FearViewModel.RegState,
    onUpdate: (ConnectFormState) -> Unit,
    onConnect: () -> Unit,
    onRegister: () -> Unit,
    onProbeServer: (String, Int) -> Unit,
    onDismissError: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val colors = LocalFearColors.current
    var serverDropdownOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    // Перепроверяем регистрацию каждый раз когда меняется host или port.
    LaunchedEffect(form.host, form.port) { onProbeServer(form.host, form.port) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ── Логотип ───────────────────────────────────────────────
        Image(
            painter = painterResource(R.drawable.logo_white),
            contentDescription = "F.E.A.R.",
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(top = 24.dp, bottom = 8.dp),
        )

        // ── Скроллируемая середина ────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Identity card — открывает профиль
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .clickable(onClick = onOpenProfile)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val letter = displayName.firstOrNull()?.uppercase() ?: "?"
                    Text(letter, color = colors.accent, fontSize = 22.sp,
                         fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayName.ifBlank { "Set your name" },
                         color = colors.textPrimary, fontSize = 16.sp,
                         fontWeight = FontWeight.SemiBold)
                    Text("Tap to edit profile", color = colors.textSecondary, fontSize = 11.sp)
                }
            }

            // Server preset dropdown
            Box {
                OutlinedTextField(
                    value = form.host,
                    onValueChange = { onUpdate(form.copy(host = it)) },
                    label = { Text("Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "Pick server",
                            modifier = Modifier.clickable { serverDropdownOpen = true },
                            tint = colors.textSecondary,
                        )
                    },
                    colors = textFieldThemeColors(),
                )
                DropdownMenu(
                    expanded = serverDropdownOpen,
                    onDismissRequest = { serverDropdownOpen = false },
                ) {
                    ServerPresets.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.label) },
                            onClick = {
                                onUpdate(form.copy(host = opt.host))
                                serverDropdownOpen = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.room,
                onValueChange = { onUpdate(form.copy(room = it)) },
                label = { Text("Room") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldThemeColors(),
            )

            // Advanced — port + manual key (collapsed by default).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedOpen = !advancedOpen }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.textSecondary,
                )
                Text("Advanced", color = colors.textSecondary, fontSize = 13.sp,
                     modifier = Modifier.padding(start = 4.dp))
            }

            AnimatedVisibility(visible = advancedOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = form.port.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { onUpdate(form.copy(port = it)) } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.widthIn(min = 120.dp),
                        colors = textFieldThemeColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        "Connect mode",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeButton("Auto",   form.mode == ConnectMode.AUTO,        Modifier.weight(1f)) { onUpdate(form.copy(mode = ConnectMode.AUTO))        }
                        ModeButton("Create", form.mode == ConnectMode.CREATE_ROOM, Modifier.weight(1f)) { onUpdate(form.copy(mode = ConnectMode.CREATE_ROOM)) }
                        ModeButton("Join",   form.mode == ConnectMode.JOIN_ROOM,   Modifier.weight(1f)) { onUpdate(form.copy(mode = ConnectMode.JOIN_ROOM))   }
                        ModeButton("Key",    form.mode == ConnectMode.MANUAL_KEY,  Modifier.weight(1f)) { onUpdate(form.copy(mode = ConnectMode.MANUAL_KEY))  }
                    }
                    if (form.mode == ConnectMode.MANUAL_KEY) {
                        OutlinedTextField(
                            value = form.key,
                            onValueChange = { onUpdate(form.copy(key = it)) },
                            label = { Text("Room key (base64)") },
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            colors = textFieldThemeColors(),
                        )
                    }
                }
            }

            if (errorBanner != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFB91C1C).copy(alpha = 0.18f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(errorBanner, color = Color(0xFFE57373), fontSize = 13.sp,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Filled.Close, "Dismiss", tint = Color(0xFFE57373))
                    }
                }
            }
        }

        // ── Фиксированный нижний блок: статус + кнопки ─────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RegistrationStatus(regState = regState, host = form.host)

            val canConnect  = !isConnecting && displayName.isNotBlank()
                              && regState.status == FearViewModel.RegStatus.Registered
            val canRegister = regState.status == FearViewModel.RegStatus.NotRegistered

            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor   = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Text("Connecting…", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                         modifier = Modifier.padding(start = 12.dp))
                } else {
                    Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            OutlinedButton(
                onClick = onRegister,
                enabled = canRegister,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Register", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RegistrationStatus(regState: FearViewModel.RegState, host: String) {
    val colors = LocalFearColors.current
    val (text, color) = when (regState.status) {
        FearViewModel.RegStatus.Registered ->
            "Зарегистрированы как @${regState.handle}@${regState.host}" to Color(0xFF66BB6A)
        FearViewModel.RegStatus.NotRegistered ->
            "Регистрация на $host обязательна для входа" to Color(0xFFE57373)
        FearViewModel.RegStatus.Probing ->
            "Проверяем регистрацию…" to colors.textSecondary
        FearViewModel.RegStatus.Error ->
            "Не удалось проверить регистрацию (нет связи с сервером)" to Color(0xFFFFA726)
        FearViewModel.RegStatus.Unknown ->
            "Введите сервер для проверки статуса" to colors.textSecondary
    }
    Text(text, color = color, fontSize = 12.sp,
         modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalFearColors.current
    val bg = if (selected) colors.accent else colors.surface
    val fg = if (selected) Color.White else colors.textSecondary
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldThemeColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor      = LocalFearColors.current.textPrimary,
    unfocusedTextColor    = LocalFearColors.current.textPrimary,
    focusedBorderColor    = LocalFearColors.current.accent,
    unfocusedBorderColor  = LocalFearColors.current.border,
    focusedLabelColor     = LocalFearColors.current.accent,
    unfocusedLabelColor   = LocalFearColors.current.textSecondary,
    cursorColor           = LocalFearColors.current.accent,
)
