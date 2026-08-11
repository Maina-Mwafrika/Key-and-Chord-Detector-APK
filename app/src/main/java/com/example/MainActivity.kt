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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.audio.SongAnalysisState
import com.example.ui.ChordDetectionViewModel
import com.example.ui.components.ChordDisplay
import com.example.ui.components.ChordHistorySheet
import com.example.ui.components.ChordModeToggle
import com.example.ui.components.ControlSection
import com.example.ui.components.GuitarStrings
import com.example.ui.components.InputSelector
import com.example.ui.components.VocalKeyScreen
import com.example.ui.components.WaveformAndChromaVisualizer
import com.example.ui.theme.ChordDetectorTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryGreenLight

enum class AppTab {
    CHORD_ANALYZER,
    VOCAL_KEY_FINDER
}

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

                var selectedTab by remember { mutableStateOf(AppTab.CHORD_ANALYZER) }

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
                val chordComplexityMode by viewModel.chordComplexityMode.collectAsStateWithLifecycle()
                val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
                val chordCarousel by viewModel.chordCarousel.collectAsStateWithLifecycle()

                // Vocal state
                val isRecordingVocal by viewModel.isRecordingVocal.collectAsStateWithLifecycle()
                val vocalRecordingTimeSec by viewModel.vocalRecordingTimeSec.collectAsStateWithLifecycle()
                val isAnalyzingVocal by viewModel.isAnalyzingVocal.collectAsStateWithLifecycle()
                val vocalAnalysisResult by viewModel.vocalAnalysisResult.collectAsStateWithLifecycle()
                val activeProgressionTitle by viewModel.activeProgressionTitle.collectAsStateWithLifecycle()

                val isAnalyzing = analysisState is SongAnalysisState.Analyzing
                val analysisProgress = (analysisState as? SongAnalysisState.Analyzing)?.progress ?: 0f

                LaunchedEffect(analysisState) {
                    val state = analysisState
                    if (state is SongAnalysisState.Error) {
                        Toast.makeText(context, "Song analysis failed: ${state.message}", Toast.LENGTH_SHORT).show()
                    }
                }

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

                        // Navigation Tab Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkSurface)
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedTab == AppTab.CHORD_ANALYZER) PrimaryGreen else Color.Transparent)
                                    .clickable { selectedTab = AppTab.CHORD_ANALYZER }
                                    .padding(vertical = 10.dp)
                                    .testTag("tab_chord_analyzer"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Chords",
                                        tint = if (selectedTab == AppTab.CHORD_ANALYZER) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Chord Analyzer",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTab == AppTab.CHORD_ANALYZER) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedTab == AppTab.VOCAL_KEY_FINDER) PrimaryGreen else Color.Transparent)
                                    .clickable { selectedTab = AppTab.VOCAL_KEY_FINDER }
                                    .padding(vertical = 10.dp)
                                    .testTag("tab_vocal_key_finder"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Vocal Key",
                                        tint = if (selectedTab == AppTab.VOCAL_KEY_FINDER) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Vocal Key Finder",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTab == AppTab.VOCAL_KEY_FINDER) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (selectedTab == AppTab.CHORD_ANALYZER) {
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

                            // 1b. Simple / Advanced chord-matching toggle
                            ChordModeToggle(
                                mode = chordComplexityMode,
                                onModeChange = { mode -> viewModel.setChordComplexityMode(mode) }
                            )

                            // 2. Middle Section: Guitar String Visualization (E A D G B E)
                            GuitarStrings(
                                stringEnergies = detectionResult.stringEnergies,
                                activeNotes = detectionResult.chord?.notes ?: emptyList(),
                                isDetectionActive = isPlaying
                            )

                            // 3. Center Section: Current Chord Carousel (prev/current/next) & Guitar Diagram
                            ChordDisplay(
                                detectionResult = detectionResult,
                                carousel = chordCarousel
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
                                },
                                isAnalyzing = isAnalyzing,
                                analysisProgress = analysisProgress,
                                onSeek = { targetMs -> viewModel.seekTo(targetMs) }
                            )

                            // 6. Chord History Sheet
                            ChordHistorySheet(
                                historyList = chordHistory,
                                onClearHistory = { viewModel.clearHistory() }
                            )
                        } else {
                            // Vocal Key Finder Tab
                            VocalKeyScreen(
                                isRecording = isRecordingVocal,
                                recordingTimeSec = vocalRecordingTimeSec,
                                isAnalyzing = isAnalyzingVocal,
                                analysisResult = vocalAnalysisResult,
                                activeSyntheticChord = syntheticChordPlaying,
                                activeProgressionTitle = activeProgressionTitle,
                                hasMicPermission = viewModel.hasMicPermission(),
                                onRequestMicPermission = {
                                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                },
                                onStartRecording = { viewModel.startVocalRecording() },
                                onStopRecordingAndAnalyze = { viewModel.stopVocalRecordingAndAnalyze() },
                                onTranspositionShiftChanged = { shift -> viewModel.updateVocalTranspositionShift(shift) },
                                onPlayChord = { chordName -> viewModel.playSyntheticGuitarChord(chordName) },
                                onPlayProgression = { progression -> viewModel.playProgression(progression) },
                                onReset = { viewModel.resetVocalAnalysis() }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}