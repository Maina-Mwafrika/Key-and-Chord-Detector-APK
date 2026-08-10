package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.SecondaryCyan

@Composable
fun WaveformAndChromaVisualizer(
    waveform: FloatArray,
    chromaVector: FloatArray, // 12 pitch classes
    modifier: Modifier = Modifier
) {
    val chromaNoteNames = ChromaChordDetector.NOTE_NAMES

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
            .testTag("waveform_chroma_visualizer"),
        color = DarkSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Live Audio Waveform & Chromagram Spectrum",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Waveform Oscilloscope Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f

                // Draw center guideline
                drawLine(
                    color = Color(0xFF2E3246),
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 1f
                )

                if (waveform.isNotEmpty()) {
                    val path = Path()
                    val stepX = width / (waveform.size - 1)
                    path.moveTo(0f, centerY + waveform[0] * centerY)

                    for (i in 1 until waveform.size) {
                        val x = i * stepX
                        val y = centerY + waveform[i] * centerY * 0.9f
                        path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = SecondaryCyan,
                        style = Stroke(width = 2.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 12 Chromagram Spectrum Bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in 0 until 12) {
                    val chromaVal = if (i < chromaVector.size) chromaVector[i].coerceIn(0f, 1f) else 0f
                    val noteName = chromaNoteNames[i]

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(40.dp)
                        ) {
                            val barWidth = size.width
                            val maxBarHeight = size.height
                            val currentBarHeight = (chromaVal * maxBarHeight).coerceAtLeast(3f)

                            drawRect(
                                color = if (chromaVal > 0.6f) PrimaryGreen else SecondaryCyan.copy(alpha = 0.5f + chromaVal * 0.5f),
                                topLeft = Offset(0f, maxBarHeight - currentBarHeight),
                                size = Size(barWidth, currentBarHeight)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = noteName,
                            fontSize = 9.sp,
                            fontWeight = if (chromaVal > 0.5f) FontWeight.Bold else FontWeight.Normal,
                            color = if (chromaVal > 0.5f) PrimaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
