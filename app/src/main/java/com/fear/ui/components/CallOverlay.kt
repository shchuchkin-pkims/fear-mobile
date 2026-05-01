package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.CallState

@Composable
fun CallOverlay(state: CallState, roomTitle: String, onEndCall: () -> Unit) {
    val colors = LocalFearColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(seed = roomTitle, size = 110.dp)
            Spacer(Modifier.height(8.dp))
            Text(roomTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                if (state.rttMs > 0) "Audio call · RTT ${state.rttMs} ms" else "Audio call",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(40.dp))
            IconButton(
                onClick = onEndCall,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB91C1C)),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Filled.CallEnd, "End call", modifier = Modifier.size(34.dp))
            }
        }
    }
}
