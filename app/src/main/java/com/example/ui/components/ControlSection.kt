package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.DetectionInputMode
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PrimaryGreen

@Composable
fun ControlSection(
    inputMode: DetectionInputMode,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    progressMs: Long,
    totalMs: Long,
    activeSyntheticChord: String?,
    onPlaySyntheticChord: (String) -> Unit,
    isAnalyzing: Boolean = false,
    analysisProgress: Float = 0f,
    onSeek: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val syntheticChords = listOf("C", "G", "Am", "F", "Em", "D7", "E5")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
            .testTag("controls_section"),
        color = DarkSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Master Play / Pause Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAnalyzing) {
                            "Analyzing song... ${(analysisProgress * 100).toInt()}%"
                        } else {
                            when (inputMode) {
                                DetectionInputMode.MICROPHONE -> if (isPlaying) "Real-time Mic Listening" else "Mic Idle"
                                DetectionInputMode.FILE -> if (isPlaying) "Playing & Analyzing File" else "File Analysis Stopped"
                                DetectionInputMode.YOUTUBE -> if (isPlaying) "Streaming YouTube Audio" else "YouTube Audio Ready"
                            }
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isAnalyzing) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { analysisProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = PrimaryGreen,
                            trackColor = DarkSurfaceVariant
                        )
                    } else if (inputMode != DetectionInputMode.MICROPHONE && totalMs > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${formatTime(progressMs)} / ${formatTime(totalMs)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isAnalyzing && inputMode != DetectionInputMode.MICROPHONE && totalMs > 0 && onSeek != null) {
                        IconButton(
                            onClick = { onSeek((progressMs - 10000L).coerceAtLeast(0L)) },
                            modifier = Modifier.testTag("rewind_10s_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind 10 seconds",
                                tint = PrimaryGreen
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = { if (!isAnalyzing) onTogglePlayback() },
                        containerColor = if (isAnalyzing) DarkSurfaceVariant else PrimaryGreen,
                        contentColor = Color.Black,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                        modifier = Modifier.testTag("play_pause_fab")
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                progress = { analysisProgress },
                                color = PrimaryGreen,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause Detection" else "Start Detection",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    if (!isAnalyzing && inputMode != DetectionInputMode.MICROPHONE && totalMs > 0 && onSeek != null) {
                        IconButton(
                            onClick = { onSeek((progressMs + 10000L).coerceAtMost(totalMs)) },
                            modifier = Modifier.testTag("fast_forward_10s_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Fast-forward 10 seconds",
                                tint = PrimaryGreen
                            )
                        }
                    }
                }
            }

            // Interactive Progress Slider for File / YouTube modes
            if (!isAnalyzing && inputMode != DetectionInputMode.MICROPHONE && totalMs > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                val currentFraction = (progressMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                var sliderValue by remember(progressMs) { mutableStateOf(currentFraction) }
                var isDragging by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = if (isDragging) sliderValue else currentFraction,
                        onValueChange = {
                            isDragging = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            val targetMs = (sliderValue * totalMs).toLong()
                            onSeek?.invoke(targetMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryGreen,
                            activeTrackColor = PrimaryGreen,
                            inactiveTrackColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playback_seek_slider")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Synthetic Guitar Chord Audio Generator Test Bar
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Test Chords",
                        tint = PrimaryGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Instant Chord Generator (Tap to play & test detector):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(syntheticChords) { chordName ->
                        val isPlayingThis = activeSyntheticChord == chordName
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPlayingThis) PrimaryGreen else DarkSurfaceVariant)
                                .border(
                                    1.dp,
                                    if (isPlayingThis) PrimaryGreen else DarkCardBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onPlaySyntheticChord(chordName) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = chordName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isPlayingThis) Color.Black else PrimaryGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}