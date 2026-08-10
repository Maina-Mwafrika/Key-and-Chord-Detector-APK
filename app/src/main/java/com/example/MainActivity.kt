package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.DetectionInputMode
import com.example.audio.DetectionStatus
import com.example.ui.ChordDetectionViewModel
import com.example.ui.components.ChordDisplay
import com.example.ui.components.ChordHistorySheet
import com.example.ui.components.ControlSection
import com.example.ui.components.GuitarStrings
import com.example.ui.components.InputSelector
import com.example.ui.components.WaveformAndChromaVisualizer
import com.example.ui.theme.ChordDetectorTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryGreenLight

class MainActivity : ComponentActivity() {

    private val viewModel: ChordDetectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ChordDetectorTheme {
                val context = LocalContext.current

                // Permission Launcher for Microphone
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        viewModel.startMicrophoneListening()
                    } else {
                        Toast.makeText(context, "Microphone permission required for real-time chord detection", Toast.LENGTH_SHORT).show()
                    }
                }

                val inputMode by viewModel.inputMode.collectAsStateWithLifecycle()
                val detectionResult by viewModel.detectionResult.collectAsStateWithLifecycle()
                val youTubeUrl by viewModel.youTubeUrl.collectAsStateWithLifecycle()
                val youTubeVideoInfo by viewModel.youTubeVideoInfo.collectAsStateWithLifecycle()
                val fileMetadata by viewModel.fileMetadata.collectAsStateWithLifecycle()
                val playbackProgressMs by viewModel.playbackProgressMs.collectAsStateWithLifecycle()
                val totalDurationMs by viewModel.totalDurationMs.collectAsStateWithLifecycle()
                val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
                val chordHistory by viewModel.chordHistory.collectAsStateWithLifecycle()
                val syntheticChordPlaying by viewModel.syntheticChordPlaying.collectAsStateWithLifecycle()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground,
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // App Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryGreen),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Logo",
                                        tint = Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Chord Detector",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Microphone • Audio File • YouTube",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurface)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "DSP Chroma v2.4",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryGreenLight
                                )
                            }
                        }

                        // 1. Top Section: Input Selection
                        InputSelector(
                            currentMode = inputMode,
                            onModeSelected = { mode -> viewModel.setInputMode(mode) },
                            youTubeUrl = youTubeUrl,
                            onYouTubeUrlChanged = { viewModel.updateYouTubeUrl(it) },
                            onFetchYouTube = { url -> viewModel.fetchYouTubeLink(url) },
                            selectedYouTubeInfo = youTubeVideoInfo,
                            onSelectYouTubePreset = { preset -> viewModel.selectYouTubePreset(preset) },
                            fileMetadata = fileMetadata,
                            onFileSelected = { uri -> viewModel.selectAudioFile(uri) },
                            hasMicPermission = viewModel.hasMicPermission(),
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        )

                        // 2. Middle Section: Guitar String Visualization (E A D G B E)
                        GuitarStrings(
                            stringEnergies = detectionResult.stringEnergies,
                            activeNotes = detectionResult.chord?.notes ?: emptyList(),
                            isDetectionActive = isPlaying || detectionResult.status == DetectionStatus.DETECTED
                        )

                        // 3. Center Section: Current Chord Display & Guitar Diagram
                        ChordDisplay(
                            detectionResult = detectionResult
                        )

                        // 4. Live Waveform & 12-Bin Chromagram Spectrum
                        WaveformAndChromaVisualizer(
                            waveform = detectionResult.waveform,
                            chromaVector = detectionResult.chromaVector
                        )

                        // 5. Bottom Section: Controls & Synthetic Chord Generator
                        ControlSection(
                            inputMode = inputMode,
                            isPlaying = isPlaying,
                            onTogglePlayback = {
                                if (inputMode == DetectionInputMode.MICROPHONE && !viewModel.hasMicPermission()) {
                                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                } else {
                                    viewModel.togglePlayback()
                                }
                            },
                            progressMs = playbackProgressMs,
                            totalMs = totalDurationMs,
                            activeSyntheticChord = syntheticChordPlaying,
                            onPlaySyntheticChord = { chordName ->
                                viewModel.playSyntheticGuitarChord(chordName)
                            }
                        )

                        // 6. Chord History Sheet
                        ChordHistorySheet(
                            historyList = chordHistory,
                            onClearHistory = { viewModel.clearHistory() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
