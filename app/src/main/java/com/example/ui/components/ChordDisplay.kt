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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ChordInfo
import com.example.audio.DetectionResult
import com.example.audio.DetectionStatus
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryGreenLight
import com.example.ui.theme.SecondaryCyan

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ChordDisplay(
    detectionResult: DetectionResult,
    modifier: Modifier = Modifier
) {
    var showDiagramModal by remember { mutableStateOf(false) }

    val chord = detectionResult.chord
    val confidencePct = (detectionResult.confidence * 100).toInt()
    val isDetected = detectionResult.status == DetectionStatus.DETECTED && chord != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                1.dp,
                if (isDetected) PrimaryGreen.copy(alpha = 0.6f) else DarkCardBorder,
                RoundedCornerShape(24.dp)
            )
            .testTag("chord_display_card"),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row: Status badge & Confidence tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Indicator Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            when (detectionResult.status) {
                                DetectionStatus.DETECTED -> PrimaryGreen.copy(alpha = 0.2f)
                                DetectionStatus.LISTENING -> SecondaryCyan.copy(alpha = 0.2f)
                                DetectionStatus.PROCESSING -> Color(0xFFFF9100).copy(alpha = 0.2f)
                                DetectionStatus.ERROR -> Color.Red.copy(alpha = 0.2f)
                                else -> DarkSurfaceVariant
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    when (detectionResult.status) {
                                        DetectionStatus.DETECTED -> PrimaryGreen
                                        DetectionStatus.LISTENING -> SecondaryCyan
                                        DetectionStatus.PROCESSING -> Color(0xFFFF9100)
                                        DetectionStatus.ERROR -> Color.Red
                                        else -> Color.Gray
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (detectionResult.status) {
                                DetectionStatus.DETECTED -> "Chord Identified"
                                DetectionStatus.LISTENING -> "Listening..."
                                DetectionStatus.PROCESSING -> "Analyzing Audio..."
                                DetectionStatus.ERROR -> "Error"
                                else -> "Idle"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (detectionResult.status) {
                                DetectionStatus.DETECTED -> PrimaryGreen
                                DetectionStatus.LISTENING -> SecondaryCyan
                                DetectionStatus.PROCESSING -> Color(0xFFFF9100)
                                DetectionStatus.ERROR -> Color.Red
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Confidence Percentage Pill
                if (isDetected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(PrimaryGreen)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$confidencePct% Confidence",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Hero Chord Name Display
            AnimatedContent(
                targetState = chord?.name ?: "...",
                transitionSpec = {
                    (fadeIn() + scaleIn()).with(fadeOut() + scaleOut())
                },
                label = "chord_text"
            ) { chordName ->
                Text(
                    text = chordName,
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isDetected) PrimaryGreenLight else MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.testTag("detected_chord_name")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Chord Type or Description
            Text(
                text = chord?.chordType ?: "Play or select audio to detect chords",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Prominent Key Label
            if (!detectionResult.estimatedKey.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(SecondaryCyan.copy(alpha = 0.15f))
                        .border(1.dp, SecondaryCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("estimated_key_label")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Estimated Key",
                            tint = SecondaryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = detectionResult.estimatedKey,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notes Breakdown Badges
            if (chord != null && chord.notes.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Notes:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    chord.notes.forEach { note ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, PrimaryGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = note,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGreenLight
                            )
                        }
                    }
                }
            }

            // Fretboard Diagram Trigger
            if (chord != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .clickable { showDiagramModal = !showDiagramModal }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GridOn,
                        contentDescription = "Fretboard Diagram",
                        tint = PrimaryGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showDiagramModal) "Hide Guitar Diagram" else "View Guitar Fingering Box",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryGreen
                    )
                }

                if (showDiagramModal) {
                    Spacer(modifier = Modifier.height(14.dp))
                    GuitarFretboardDiagramBox(chord = chord)
                }
            }
        }
    }
}

@Composable
fun GuitarFretboardDiagramBox(chord: ChordInfo) {
    val frets = chord.fretDiagram.frets // 6 elements: [Low E, A, D, G, B, High E]

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
        color = DarkSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${chord.name} Guitar Fretboard Diagram",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Strings & Frets Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val stringLabels = arrayOf("E2", "A2", "D3", "G3", "B3", "E4")
                for (s in 0 until 6) {
                    val fretVal = frets[s]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringLabels[s],
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    when (fretVal) {
                                        -1 -> Color.Red.copy(alpha = 0.2f)
                                        0 -> PrimaryGreen.copy(alpha = 0.2f)
                                        else -> PrimaryGreen
                                    }
                                )
                                .border(
                                    1.dp,
                                    when (fretVal) {
                                        -1 -> Color.Red
                                        0 -> PrimaryGreen
                                        else -> PrimaryGreen
                                    },
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (fretVal) {
                                    -1 -> "X"
                                    0 -> "O"
                                    else -> "$fretVal"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = when (fretVal) {
                                    -1 -> Color.Red
                                    0 -> PrimaryGreen
                                    else -> Color.Black
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
