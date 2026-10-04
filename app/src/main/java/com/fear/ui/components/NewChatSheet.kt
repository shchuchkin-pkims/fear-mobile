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
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PersonAdd
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
 * Bottom-sheet shown when the user taps the floating "+" in the sidebar.
 * Offers three primary actions for starting a new chat:
 *   1. Add contact     — open the existing contacts dialog (DM flow).
 *   2. Join room       — ask for a room name, JOIN existing room.
 *   3. Create new room — ask for a room name, CREATE with a fresh key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    onDismiss: () -> Unit,
    onAddContact: () -> Unit,
    onJoinRoom: () -> Unit,
    onCreateRoom: () -> Unit,
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
                text = "New chat",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ItemRow(
                Icons.Filled.PersonAdd,
                "Add contact",
                "Add a person by their nickname@server.",
            ) { onAddContact(); onDismiss() }
            ItemRow(
                Icons.Filled.Login,
                "Join room",
                "Enter an existing room. The key is fetched from a participant.",
            ) { onJoinRoom(); onDismiss() }
            ItemRow(
                Icons.Filled.GroupAdd,
                "Create new room",
                "Pick a name. A fresh encryption key is generated automatically.",
            ) { onCreateRoom(); onDismiss() }
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
