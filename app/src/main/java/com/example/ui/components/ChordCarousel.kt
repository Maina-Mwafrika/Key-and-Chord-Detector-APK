package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ChordInfo
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryGreenLight

/**
 * Shows the previous, current, and next chord side-by-side, carousel-style: the current
 * chord is large and fully opaque in the center, while the previous/next chords are
 * smaller and faint on either side, giving a sense of the progression flowing through the
 * song. When there's no known "next" chord (live/streaming input with no precomputed
 * timeline), that slot renders as an empty placeholder rather than guessing.
 */
@Composable
fun ChordCarousel(
    previous: ChordInfo?,
    current: ChordInfo?,
    next: ChordInfo?,
    isDetected: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CarouselSlot(
            chordName = previous?.name,
            emphasis = SlotEmphasis.SIDE
        )

        CarouselSlot(
            chordName = current?.name ?: "...",
            emphasis = if (isDetected) SlotEmphasis.CENTER_ACTIVE else SlotEmphasis.CENTER_IDLE
        )

        CarouselSlot(
            chordName = next?.name,
            emphasis = SlotEmphasis.SIDE
        )
    }
}

private enum class SlotEmphasis { SIDE, CENTER_ACTIVE, CENTER_IDLE }

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun CarouselSlot(chordName: String?, emphasis: SlotEmphasis) {
    val boxSize = if (emphasis == SlotEmphasis.SIDE) 64.dp else 108.dp
    val fontSize = if (emphasis == SlotEmphasis.SIDE) 20.sp else 42.sp
    val textColor = when (emphasis) {
        SlotEmphasis.CENTER_ACTIVE -> PrimaryGreenLight
        SlotEmphasis.CENTER_IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        SlotEmphasis.SIDE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val boxAlpha = if (emphasis == SlotEmphasis.SIDE) 0.4f else 1f

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(boxSize)
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurfaceVariant.copy(alpha = boxAlpha))
            .then(
                if (emphasis == SlotEmphasis.CENTER_ACTIVE) {
                    Modifier.border(1.5.dp, PrimaryGreen.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = chordName ?: "",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.85f)) with (fadeOut() + scaleOut(targetScale = 0.85f))
            },
            label = "carousel_slot_chord"
        ) { name ->
            Text(
                text = name,
                fontSize = fontSize,
                fontWeight = if (emphasis == SlotEmphasis.SIDE) FontWeight.Bold else FontWeight.Black,
                color = textColor
            )
        }
    }
}