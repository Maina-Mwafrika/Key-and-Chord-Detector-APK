package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ChromaChordDetector
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.StringA2Color
import com.example.ui.theme.StringB3Color
import com.example.ui.theme.StringD3Color
import com.example.ui.theme.StringE2Color
import com.example.ui.theme.StringE4Color
import com.example.ui.theme.StringG3Color
import kotlin.math.sin

@Composable
fun GuitarStrings(
    stringEnergies: FloatArray, // 6 elements: E2, A2, D3, G3, B3, E4
    activeNotes: List<String>,
    isDetectionActive: Boolean,
    modifier: Modifier = Modifier
) {
    val stringColors = arrayOf(
        StringE2Color, // String 6: Low E
        StringA2Color, // String 5: A
        StringD3Color, // String 4: D
        StringG3Color, // String 3: G
        StringB3Color, // String 2: B
        StringE4Color  // String 1: High E
    )

    val stringNames = ChromaChordDetector.GUITAR_STRING_NAMES
    val stringBaseThickness = arrayOf(6f, 5f, 4.2f, 3.5f, 2.8f, 2.0f)

    // Infinite transition for string vibration animation
    val infiniteTransition = rememberInfiniteTransition(label = "string_vibration")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
            .testTag("guitar_strings_container"),
        color = DarkSurface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp, horizontal = 24.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val stringSpacing = canvasHeight / 5f

                // Draw Fretboard Wood Guidelines & Frets
                val fretCount = 5
                val fretSpacing = canvasWidth / fretCount
                for (f in 1 until fretCount) {
                    val fretX = f * fretSpacing
                    drawLine(
                        color = Color(0xFF2E3246),
                        start = Offset(fretX, 0f),
                        end = Offset(fretX, canvasHeight),
                        strokeWidth = 2.5f
                    )
                }

                // Draw 6 Guitar Strings
                for (i in 0 until 6) {
                    val y = i * stringSpacing
                    val energy = if (i < stringEnergies.size) stringEnergies[i] else 0f
                    val color = stringColors[i]
                    val baseThickness = stringBaseThickness[i]

                    val vibrationAmplitude = if (isDetectionActive) energy * 12f else 0f

                    if (vibrationAmplitude > 0.5f) {
                        // Draw vibrating string sine wave path
                        val path = Path()
                        path.moveTo(0f, y)

                        val steps = 40
                        val stepX = canvasWidth / steps
                        for (s in 1..steps) {
                            val x = s * stepX
                            val sineOffset = sin(wavePhase + (x / canvasWidth) * 3f * Math.PI.toFloat()) * vibrationAmplitude
                            path.lineTo(x, y + sineOffset)
                        }

                        // Outer Neon Glow
                        drawPath(
                            path = path,
                            color = color.copy(alpha = (0.3f + energy * 0.5f).coerceIn(0f, 0.8f)),
                            style = Stroke(width = baseThickness + 8f)
                        )

                        // Solid Core String
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = baseThickness + 1f)
                        )
                    } else {
                        // Straight String
                        // Outer Glow
                        drawLine(
                            color = color.copy(alpha = 0.25f),
                            start = Offset(0f, y),
                            end = Offset(canvasWidth, y),
                            strokeWidth = baseThickness + 4f
                        )
                        // Core
                        drawLine(
                            color = color,
                            start = Offset(0f, y),
                            end = Offset(canvasWidth, y),
                            strokeWidth = baseThickness
                        )
                    }
                }
            }

            // String Name Badges (Left Column: E A D G B E)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0 until 6) {
                    val color = stringColors[i]
                    val stringNoteName = stringNames[i].substring(0, 1) // "E", "A", "D", "G", "B", "E"
                    val isNoteInChord = activeNotes.contains(stringNoteName)

                    Box(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isNoteInChord) color else DarkSurface
                            )
                            .border(1.dp, color, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringNames[i],
                            color = if (isNoteInChord) Color.Black else color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
