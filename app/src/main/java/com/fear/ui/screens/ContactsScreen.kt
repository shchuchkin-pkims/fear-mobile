package com.fear.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.data.ContactEntity
import com.fear.ui.theme.LocalFearColors

/**
 * Address-book screen — list of saved contacts with add/remove. Until
 * Phase B-5 wires DMs into the chat list, this is the main place to
 * see who you've added.
 */
@Composable
fun ContactsScreen(
    contacts: List<ContactEntity>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (ContactEntity) -> Unit,
    onOpenChat: (ContactEntity) -> Unit,
) {
    val colors = LocalFearColors.current
    Box(modifier = Modifier
        .fillMaxSize()
        .background(colors.background)
        .statusBarsPadding()
        .navigationBarsPadding()) {

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                         tint = colors.textPrimary)
                }
                Text("Contacts", color = colors.textPrimary, fontSize = 18.sp,
                     fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                Text("${contacts.size}", color = colors.textSecondary, fontSize = 13.sp,
                     modifier = Modifier.padding(end = 12.dp))
            }
            HorizontalDivider(color = colors.border)
            if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No contacts yet", color = colors.textPrimary, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to add someone by their nickname@server.",
                         color = colors.textSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.identityPkB64 }) { c ->
                        ContactRow(c,
                            onOpenChat = { onOpenChat(c) },
                            onRemove   = { onRemove(c) })
                        HorizontalDivider(color = colors.border)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = colors.accent,
        ) { Icon(Icons.Filled.Add, "Add contact") }
    }
}

@Composable
private fun ContactRow(
    c: ContactEntity,
    onOpenChat: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onOpenChat)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(c.displayName.firstOrNull()?.uppercase() ?: "?",
                 color = colors.accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.displayName, color = colors.textPrimary, fontSize = 15.sp,
                 fontWeight = FontWeight.SemiBold)
            val handle = if (c.handle != null && c.server != null) "${c.handle}@${c.server}"
                         else c.identityPkB64.take(12) + "…"
            Text(handle, color = colors.textSecondary, fontSize = 12.sp)
        }
        IconButton(onClick = onOpenChat) {
            Icon(Icons.AutoMirrored.Filled.Chat, "Open chat",
                 tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "Remove", tint = colors.textSecondary,
                 modifier = Modifier.size(18.dp))
        }
    }
}
