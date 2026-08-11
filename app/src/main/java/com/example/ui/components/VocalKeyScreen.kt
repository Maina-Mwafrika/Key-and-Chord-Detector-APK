package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ChordInfo
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryGreenLight
import com.example.vocal.VocalAnalysisResult
import com.example.vocal.VocalProgression

@Composable
fun VocalKeyScreen(
    isRecording: Boolean,
    recordingTimeSec: Int,
    isAnalyzing: Boolean,
    analysisResult: VocalAnalysisResult?,
    activeSyntheticChord: String?,
    activeProgressionTitle: String?,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecordingAndAnalyze: () -> Unit,
    onTranspositionShiftChanged: (Int) -> Unit,
    onPlayChord: (String) -> Unit,
    onPlayProgression: (VocalProgression) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Feature Header Banner
        HeaderBanner()

        // Singing Recording Section
        RecordingSection(
            isRecording = isRecording,
            recordingTimeSec = recordingTimeSec,
            isAnalyzing = isAnalyzing,
            analysisResult = analysisResult,
            hasMicPermission = hasMicPermission,
            onRequestMicPermission = onRequestMicPermission,
            onStartRecording = onStartRecording,
            onStopRecordingAndAnalyze = onStopRecordingAndAnalyze,
            onReset = onReset
        )

        // Analysis Results Cards
        if (analysisResult != null && !isRecording && !isAnalyzing) {
            // Card 1: Key Recommendation & Voice Profile
            KeyRecommendationCard(analysisResult)

            // Card 2: Key Shift / Transposition Tuner
            TranspositionTunerCard(
                analysisResult = analysisResult,
                onTranspositionShiftChanged = onTranspositionShiftChanged
            )

            // Card 3: Guitar Capo Guide
            CapoGuideCard(analysisResult)

            // Card 4: Diatonic Chords for Suggested Key
            DiatonicChordsCard(
                analysisResult = analysisResult,
                activeSyntheticChord = activeSyntheticChord,
                onPlayChord = onPlayChord
            )

            // Card 5: Suggested Chord Progressions
            ChordProgressionsCard(
                analysisResult = analysisResult,
                activeProgressionTitle = activeProgressionTitle,
                onPlayProgression = onPlayProgression
            )
        }
    }
}

@Composable
private fun HeaderBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PrimaryGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Vocal Mic",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Vocal Key & Pitch Assistant",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Find your optimal singing key & comfortable guitar chords",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecordingSection(
    isRecording: Boolean,
    recordingTimeSec: Int,
    isAnalyzing: Boolean,
    analysisResult: VocalAnalysisResult?,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecordingAndAnalyze: () -> Unit,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                !isRecording && !isAnalyzing && analysisResult == null -> {
                    IdleState(
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = onRequestMicPermission,
                        onStartRecording = onStartRecording
                    )
                }
                isRecording -> {
                    RecordingState(
                        recordingTimeSec = recordingTimeSec,
                        onStopRecordingAndAnalyze = onStopRecordingAndAnalyze
                    )
                }
                isAnalyzing -> {
                    AnalyzingState()
                }
                analysisResult != null -> {
                    AnalysisCompleteState(onReset = onReset)
                }
            }
        }
    }
}

@Composable
private fun IdleState(
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit
) {
    Text(
        text = "Sing a Stanza or Verse",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Sing naturally into your mic for 5 to 10 seconds. We'll determine your vocal tessitura, key center, and recommend transposed chords for maximum singing comfort.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = {
            if (!hasMicPermission) {
                onRequestMicPermission()
            } else {
                onStartRecording()
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .height(52.dp)
            .testTag("start_vocal_recording_button")
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Mic",
            tint = Color.Black
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (!hasMicPermission) "Grant Mic Permission" else "Start Singing Recording",
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun RecordingState(
    recordingTimeSec: Int,
    onStopRecordingAndAnalyze: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(Color(0xFFE53935).copy(alpha = 0.25f))
            .border(2.dp, Color(0xFFE53935), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Listening",
            tint = Color(0xFFE53935),
            modifier = Modifier.size(40.dp)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Listening to your voice...",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "${formatTime(recordingTimeSec)} / 00:15",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = PrimaryGreenLight
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onStopRecordingAndAnalyze,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .height(48.dp)
            .testTag("stop_vocal_recording_button")
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "Stop",
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Stop & Analyze Key",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun AnalyzingState() {
    CircularProgressIndicator(
        color = PrimaryGreen,
        strokeWidth = 3.dp,
        modifier = Modifier.size(44.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Analyzing vocal pitch spectrum & tessitura...",
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun AnalysisCompleteState(onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Analyzed",
                tint = PrimaryGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Analysis Complete",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
            onClick = onReset,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = DarkSurfaceVariant,
                contentColor = PrimaryGreenLight
            ),
            border = BorderStroke(1.dp, PrimaryGreen.copy(alpha = 0.6f)),
            modifier = Modifier.testTag("sing_again_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset",
                    tint = PrimaryGreenLight,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Sing Again",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreenLight
                )
            }
        }
    }
}

@Composable
private fun KeyRecommendationCard(analysisResult: VocalAnalysisResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "YOUR VOCAL KEY RECOMMENDATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = PrimaryGreen,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sung Key",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = analysisResult.sungKey,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (analysisResult.transpositionOffset == 0) 
                            "Key Match 100%" 
                        else 
                            "${if (analysisResult.transpositionOffset > 0) "+" else ""}${analysisResult.transpositionOffset} semitones",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryGreenLight
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Best Fit Key",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = analysisResult.suggestedKey,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = PrimaryGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.7f))
                    .padding(12.dp)
            ) {
                Text(
                    text = analysisResult.comfortAssessment,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatPill(title = "Voice Classification", value = analysisResult.voiceType)
                StatPill(title = "Lowest Note", value = analysisResult.lowestNote)
                StatPill(title = "Highest Note", value = analysisResult.highestNote)
                StatPill(title = "Median Note", value = analysisResult.medianNote)
            }
        }
    }
}

@Composable
private fun TranspositionTunerCard(
    analysisResult: VocalAnalysisResult,
    onTranspositionShiftChanged: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Tune",
                        tint = PrimaryGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Key Transposition Tuner",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "${if (analysisResult.transpositionOffset > 0) "+" else ""}${analysisResult.transpositionOffset} semitones",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreen
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { 
                        val newOffset = (analysisResult.transpositionOffset - 1).coerceAtLeast(-6)
                        onTranspositionShiftChanged(newOffset)
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .testTag("transpose_down_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Transpose Down",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = analysisResult.suggestedKey,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Adjust key for singer pitch",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { 
                        val newOffset = (analysisResult.transpositionOffset + 1).coerceAtMost(6)
                        onTranspositionShiftChanged(newOffset)
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .testTag("transpose_up_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Transpose Up",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun CapoGuideCard(analysisResult: VocalAnalysisResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PrimaryGreen.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Guitar Capo",
                    tint = PrimaryGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "GUITAR CAPO GUIDE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreenLight
                )
                Text(
                    text = analysisResult.capoGuide,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DiatonicChordsCard(
    analysisResult: VocalAnalysisResult,
    activeSyntheticChord: String?,
    onPlayChord: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DIATONIC CHORDS IN ${analysisResult.suggestedKey.uppercase()}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = PrimaryGreen,
                letterSpacing = 1.sp
            )
            Text(
                text = "Tap any chord chip to hear the guitar synth audio preview",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Use LazyRow for better performance with many chords
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(analysisResult.diatonicChords.take(7)) { chord ->
                    ChordChip(
                        chord = chord,
                        isPlaying = activeSyntheticChord == chord.name,
                        onClick = { onPlayChord(chord.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChordChip(
    chord: ChordInfo,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPlaying) PrimaryGreen else DarkSurfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = chord.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) Color.Black else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChordProgressionsCard(
    analysisResult: VocalAnalysisResult,
    activeProgressionTitle: String?,
    onPlayProgression: (VocalProgression) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "POPULAR CHORD PROGRESSIONS FOR THIS KEY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = PrimaryGreen,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            analysisResult.commonProgressions.forEach { progression ->
                ProgressionItem(
                    progression = progression,
                    isPlaying = activeProgressionTitle == progression.title,
                    onPlayProgression = onPlayProgression
                )
            }
        }
    }
}

@Composable
private fun ProgressionItem(
    progression: VocalProgression,
    isPlaying: Boolean,
    onPlayProgression: (VocalProgression) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = progression.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "(${progression.romanNumerals})",
                        fontSize = 11.sp,
                        color = PrimaryGreenLight,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (progression.isRecognizedFromRecording) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryGreen)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Recognized",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    progression.chordNames.forEach { chordName ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkSurface)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = chordName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = { onPlayProgression(progression) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) PrimaryGreen else DarkSurface)
                    .testTag("play_progression_${progression.title.replace(" ", "_")}")
            ) {
                if (isPlaying) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Progression",
                        tint = PrimaryGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}