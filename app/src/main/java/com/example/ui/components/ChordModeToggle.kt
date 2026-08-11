package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ChordComplexityMode
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PrimaryGreen

@Composable
fun ChordModeToggle(
    mode: ChordComplexityMode,
    onModeChange: (ChordComplexityMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .testTag("chord_mode_toggle"),
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeOption(
                label = "Simple (Diatonic)",
                isSelected = mode == ChordComplexityMode.SIMPLE,
                onClick = { onModeChange(ChordComplexityMode.SIMPLE) }
            )
            Spacer(modifier = Modifier.width(4.dp))
            ModeOption(
                label = "Advanced (All)",
                isSelected = mode == ChordComplexityMode.ADVANCED,
                onClick = { onModeChange(ChordComplexityMode.ADVANCED) }
            )
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) PrimaryGreen else Color.Transparent
    val textColor = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}
