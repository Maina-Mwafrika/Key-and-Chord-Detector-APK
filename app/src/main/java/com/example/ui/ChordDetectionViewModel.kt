package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFileMetadata
import com.example.audio.ChordCarouselState
import com.example.audio.ChordComplexityMode
import com.example.audio.ChordHistoryItem
import com.example.audio.ChordInfo
import com.example.audio.ChromaChordDetector
import com.example.audio.DetectionInputMode
import com.example.audio.DetectionResult
import com.example.audio.DetectionStatus
import com.example.audio.FileProcessor
import com.example.audio.MicrophoneRecorder
import com.example.audio.SongAnalyzer
import com.example.audio.SongAnalysisState
import com.example.audio.SongChordTimeline
import com.example.youtube.YouTubeExtractor
import com.example.youtube.YouTubeVideoInfo
import com.example.vocal.VocalKeyAnalyzer
import com.example.vocal.VocalAnalysisResult
import com.example.vocal.VocalProgression
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class ChordDetectionViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = ChromaChordDetector(sampleRate = 44100)
    private val micRecorder = MicrophoneRecorder(application)
    private val fileProcessor = FileProcessor(application)
    private val youtubeExtractor = YouTubeExtractor(application)
    private val songAnalyzer = SongAnalyzer(application)

    private val _inputMode = MutableStateFlow(DetectionInputMode.MICROPHONE)
    val inputMode: StateFlow<DetectionInputMode> = _inputMode.asStateFlow()

    private val _detectionResult = MutableStateFlow(DetectionResult())
    val detectionResult: StateFlow<DetectionResult> = _detectionResult.asStateFlow()

    private val _status = MutableStateFlow(DetectionStatus.IDLE)
    val status: StateFlow<DetectionStatus> = _status.asStateFlow()

    private val _youTubeUrl = MutableStateFlow("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    val youTubeUrl: StateFlow<String> = _youTubeUrl.asStateFlow()

    private val _youTubeVideoInfo = MutableStateFlow<YouTubeVideoInfo?>(YouTubeExtractor.PRESET_DEMO_VIDEOS[0])
    val youTubeVideoInfo: StateFlow<YouTubeVideoInfo?> = _youTubeVideoInfo.asStateFlow()

    private val _fileMetadata = MutableStateFlow<AudioFileMetadata?>(null)
    val fileMetadata: StateFlow<AudioFileMetadata?> = _fileMetadata.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    private val _playbackProgressMs = MutableStateFlow(0L)
    val playbackProgressMs: StateFlow<Long> = _playbackProgressMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _chordHistory = MutableStateFlow<List<ChordHistoryItem>>(emptyList())
    val chordHistory: StateFlow<List<ChordHistoryItem>> = _chordHistory.asStateFlow()

    private val _syntheticChordPlaying = MutableStateFlow<String?>(null)
    val syntheticChordPlaying: StateFlow<String?> = _syntheticChordPlaying.asStateFlow()

    // --- Offline song analysis (upload -> process -> smooth playback) ---
    private val _chordComplexityMode = MutableStateFlow(ChordComplexityMode.SIMPLE)
    val chordComplexityMode: StateFlow<ChordComplexityMode> = _chordComplexityMode.asStateFlow()

    private val _songTimeline = MutableStateFlow<SongChordTimeline?>(null)
    val songTimeline: StateFlow<SongChordTimeline?> = _songTimeline.asStateFlow()

    private val _analysisState = MutableStateFlow<SongAnalysisState>(SongAnalysisState.Idle)
    val analysisState: StateFlow<SongAnalysisState> = _analysisState.asStateFlow()

    private val _chordCarousel = MutableStateFlow(ChordCarouselState())
    val chordCarousel: StateFlow<ChordCarouselState> = _chordCarousel.asStateFlow()

    private var analysisJob: Job? = null

    // --- Vocalist Key Finder State ---
    private val vocalAnalyzer = VocalKeyAnalyzer(sampleRate = 44100)
    private val vocalPcmBuffers = mutableListOf<ShortArray>()

    private val _isRecordingVocal = MutableStateFlow(false)
    val isRecordingVocal: StateFlow<Boolean> = _isRecordingVocal.asStateFlow()

    private val _vocalRecordingTimeSec = MutableStateFlow(0)
    val vocalRecordingTimeSec: StateFlow<Int> = _vocalRecordingTimeSec.asStateFlow()

    private val _isAnalyzingVocal = MutableStateFlow(false)
    val isAnalyzingVocal: StateFlow<Boolean> = _isAnalyzingVocal.asStateFlow()

    private val _vocalAnalysisResult = MutableStateFlow<VocalAnalysisResult?>(null)
    val vocalAnalysisResult: StateFlow<VocalAnalysisResult?> = _vocalAnalysisResult.asStateFlow()

    private val _activeProgressionTitle = MutableStateFlow<String?>(null)
    val activeProgressionTitle: StateFlow<String?> = _activeProgressionTitle.asStateFlow()

    private var vocalTimerJob: Job? = null
    private var progressionJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastRecordedChordName: String? = null
    private var syntheticJob: Job? = null

    fun setInputMode(mode: DetectionInputMode) {
        stopAllProcessing()
        analysisJob?.cancel()
        detector.resetState()
        _inputMode.value = mode
        _detectionResult.value = DetectionResult(status = DetectionStatus.IDLE)
        _status.value = DetectionStatus.IDLE
        _isPlaying.value = false
        _songTimeline.value = null
        _analysisState.value = SongAnalysisState.Idle
        _chordCarousel.value = ChordCarouselState()
    }

    fun hasMicPermission(): Boolean {
        return micRecorder.hasPermission()
    }

    /**
     * Switches between "Simple" (diatonic-only) and "Advanced" (full vocabulary, including
     * borrowed/chromatic chords) chord matching. If a song is already loaded in FILE mode,
     * automatically re-analyzes it under the new mode.
     */
    fun setChordComplexityMode(mode: ChordComplexityMode) {
        if (_chordComplexityMode.value == mode) return
        _chordComplexityMode.value = mode
        val uri = _selectedUri.value
        if (_inputMode.value == DetectionInputMode.FILE && uri != null) {
            analyzeSelectedFile(uri)
        }
    }

    fun startMicrophoneListening() {
        if (_inputMode.value != DetectionInputMode.MICROPHONE) return

        stopAllProcessing()
        _status.value = DetectionStatus.LISTENING
        _isPlaying.value = true

        micRecorder.startRecording(
            scope = viewModelScope,
            bufferSizeSamples = 2048,
            onPcmBuffer = { pcmChunk ->
                processPcmBuffer(pcmChunk, sourceLabel = "Microphone")
            },
            onError = { errorMsg ->
                _status.value = DetectionStatus.ERROR
                _isPlaying.value = false
                _detectionResult.value = DetectionResult(
                    status = DetectionStatus.ERROR,
                    errorMessage = errorMsg
                )
            }
        )
    }

    fun stopMicrophoneListening() {
        micRecorder.stopRecording()
        _status.value = DetectionStatus.IDLE
        _isPlaying.value = false
    }

    fun updateYouTubeUrl(url: String) {
        _youTubeUrl.value = url
    }

    fun fetchYouTubeLink(url: String) {
        val info = youtubeExtractor.fetchVideoInfo(url)
        _youTubeVideoInfo.value = info
        selectYouTubePreset(info)
    }

    fun selectYouTubePreset(presetInfo: YouTubeVideoInfo) {
        stopAllProcessing()
        _youTubeVideoInfo.value = presetInfo
        _youTubeUrl.value = "https://www.youtube.com/watch?v=${presetInfo.videoId}"
        _totalDurationMs.value = presetInfo.durationSeconds * 1000L
        _playbackProgressMs.value = 0L
        _status.value = DetectionStatus.IDLE
        _isPlaying.value = false
        _chordCarousel.value = ChordCarouselState()
    }

    /**
     * Selecting a file kicks off offline analysis immediately (decode -> determine one
     * overall key -> best chord per measure) before any playback happens. Playback (once
     * started) then looks up chords from that precomputed timeline instead of re-detecting
     * live, so the displayed progression is smooth and stable.
     */
    fun selectAudioFile(uri: Uri) {
        stopAllProcessing()
        _selectedUri.value = uri
        val meta = fileProcessor.getFileMetadata(uri)
        _fileMetadata.value = meta
        if (meta != null) {
            _totalDurationMs.value = meta.durationMs
        }
        _playbackProgressMs.value = 0L
        _status.value = DetectionStatus.IDLE
        _isPlaying.value = false
        _chordCarousel.value = ChordCarouselState()
        analyzeSelectedFile(uri)
    }

    private fun analyzeSelectedFile(uri: Uri) {
        analysisJob?.cancel()
        _songTimeline.value = null
        _chordCarousel.value = ChordCarouselState()
        _analysisState.value = SongAnalysisState.Analyzing(0f)

        analysisJob = viewModelScope.launch {
            try {
                val timeline = songAnalyzer.analyze(
                    uri = uri,
                    chordMode = _chordComplexityMode.value,
                    onProgress = { progress ->
                        _analysisState.value = SongAnalysisState.Analyzing(progress.fraction, progress.stage)
                    }
                )
                _songTimeline.value = timeline
                _analysisState.value = SongAnalysisState.Ready
            } catch (e: Exception) {
                _analysisState.value = SongAnalysisState.Error(e.localizedMessage ?: "Analysis failed")
            }
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    fun startPlayback() {
        when (_inputMode.value) {
            DetectionInputMode.MICROPHONE -> {
                startMicrophoneListening()
            }
            DetectionInputMode.FILE -> {
                val uri = _selectedUri.value
                if (uri == null) {
                    _status.value = DetectionStatus.ERROR
                    _detectionResult.value = DetectionResult(
                        status = DetectionStatus.ERROR,
                        errorMessage = "Please tap 'Select Audio File' to pick a song file"
                    )
                    return
                }
                stopAllProcessing()
                _status.value = DetectionStatus.PROCESSING
                _isPlaying.value = true

                val startMs = if (_playbackProgressMs.value >= _totalDurationMs.value) 0L else _playbackProgressMs.value

                fileProcessor.processAndPlayAudioUri(
                    scope = viewModelScope,
                    uri = uri,
                    detector = detector,
                    startMs = startMs,
                    onProgressUpdate = { currentMs, totalMs ->
                        _playbackProgressMs.value = currentMs
                        _totalDurationMs.value = totalMs
                        updateCarouselFromTimeline(currentMs)
                    },
                    onPcmChunk = { chunk ->
                        // Live waveform/chroma still comes from the streaming detector (kept
                        // reactive for the visualizer), but the actual displayed chord/key
                        // for an analyzed song is overridden from the precomputed timeline
                        // below so it's rock-solid and synced to the real measure boundaries.
                        val liveResult = detector.processPcmSamples(chunk)
                        val timeline = _songTimeline.value
                        val result = if (timeline != null) {
                            val measure = timeline.measures.getOrNull(
                                timeline.measureIndexAt(_playbackProgressMs.value)
                            )
                            liveResult.copy(
                                chord = measure?.chord,
                                confidence = measure?.confidence ?: 0f,
                                estimatedKey = timeline.key,
                                status = if (measure?.chord != null) DetectionStatus.DETECTED else DetectionStatus.LISTENING
                            )
                        } else {
                            liveResult
                        }
                        _detectionResult.value = result
                        maybeRecordHistoryEntry(result, _fileMetadata.value?.fileName ?: "Audio File")
                    },
                    onError = { err ->
                        _status.value = DetectionStatus.ERROR
                        _isPlaying.value = false
                        _detectionResult.value = DetectionResult(
                            status = DetectionStatus.ERROR,
                            errorMessage = err
                        )
                    }
                )
            }
            DetectionInputMode.YOUTUBE -> {
                val info = _youTubeVideoInfo.value ?: YouTubeExtractor.PRESET_DEMO_VIDEOS[0]
                stopAllProcessing()
                _status.value = DetectionStatus.PROCESSING
                _isPlaying.value = true

                youtubeExtractor.startStreamingYouTubeAudio(
                    scope = viewModelScope,
                    videoInfo = info,
                    sampleRate = 44100,
                    onProgressUpdate = { currentMs, totalMs ->
                        _playbackProgressMs.value = currentMs
                        _totalDurationMs.value = totalMs
                    },
                    onPcmChunk = { chunk ->
                        processPcmBuffer(chunk, sourceLabel = "YouTube: ${info.title}")
                    },
                    onError = { err ->
                        _status.value = DetectionStatus.ERROR
                        _isPlaying.value = false
                        _detectionResult.value = DetectionResult(
                            status = DetectionStatus.ERROR,
                            errorMessage = err
                        )
                    }
                )
            }
        }
    }

    /**
     * Seeks playback to a specific position in milliseconds. Updates current chord
     * and carousel state immediately from the precomputed song timeline so UI visualizer
     * is completely smooth and non-distorted.
     */
    fun seekTo(positionMs: Long) {
        val duration = _totalDurationMs.value
        val targetMs = positionMs.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        _playbackProgressMs.value = targetMs

        // 1. Immediately update timeline carousel & current chord view
        updateCarouselFromTimeline(targetMs)
        val timeline = _songTimeline.value
        if (timeline != null) {
            val measureIndex = timeline.measureIndexAt(targetMs)
            val measure = timeline.measures.getOrNull(measureIndex)
            _detectionResult.value = _detectionResult.value.copy(
                chord = measure?.chord,
                confidence = measure?.confidence ?: 0f,
                estimatedKey = timeline.key,
                status = if (measure?.chord != null) DetectionStatus.DETECTED else DetectionStatus.LISTENING,
                stringEnergies = if (_isPlaying.value) _detectionResult.value.stringEnergies else FloatArray(6)
            )
        }

        // 2. If actively playing in FILE mode, seek/restart FileProcessor at targetMs
        if (_inputMode.value == DetectionInputMode.FILE && _selectedUri.value != null && _isPlaying.value) {
            val uri = _selectedUri.value!!
            detector.resetState()
            fileProcessor.stopPlayback()
            fileProcessor.processAndPlayAudioUri(
                scope = viewModelScope,
                uri = uri,
                detector = detector,
                startMs = targetMs,
                onProgressUpdate = { currentMs, totalMs ->
                    _playbackProgressMs.value = currentMs
                    _totalDurationMs.value = totalMs
                    updateCarouselFromTimeline(currentMs)
                },
                onPcmChunk = { chunk ->
                    val liveResult = detector.processPcmSamples(chunk)
                    val tl = _songTimeline.value
                    val result = if (tl != null) {
                        val m = tl.measures.getOrNull(tl.measureIndexAt(_playbackProgressMs.value))
                        liveResult.copy(
                            chord = m?.chord,
                            confidence = m?.confidence ?: 0f,
                            estimatedKey = tl.key,
                            status = if (m?.chord != null) DetectionStatus.DETECTED else DetectionStatus.LISTENING
                        )
                    } else {
                        liveResult
                    }
                    _detectionResult.value = result
                    maybeRecordHistoryEntry(result, _fileMetadata.value?.fileName ?: "Audio File")
                },
                onError = { err ->
                    _status.value = DetectionStatus.ERROR
                    _isPlaying.value = false
                    _detectionResult.value = DetectionResult(
                        status = DetectionStatus.ERROR,
                        errorMessage = err
                    )
                }
            )
        }
    }

    fun pausePlayback() {
        stopAllProcessing()
        _isPlaying.value = false
        _status.value = DetectionStatus.IDLE
        _detectionResult.value = _detectionResult.value.copy(stringEnergies = FloatArray(6))
    }

    /**
     * Live streaming detection path (microphone, YouTube's synthetic stream, and the test
     * chord generator). Uploaded-file playback with a ready timeline bypasses this and uses
     * updateCarouselFromTimeline + the timeline override in startPlayback's FILE branch
     * instead.
     */
    private fun processPcmBuffer(pcmChunk: ShortArray, sourceLabel: String) {
        val result = detector.processPcmSamples(pcmChunk)
        _detectionResult.value = result
        updateLiveCarousel(result.chord)
        maybeRecordHistoryEntry(result, sourceLabel)
    }

    /**
     * Carousel update for the precomputed-timeline (offline-analyzed song) playback path:
     * previous/current/next are simply looked up by measure index, so "next" is always
     * known ahead of time.
     */
    private fun updateCarouselFromTimeline(currentMs: Long) {
        val timeline = _songTimeline.value ?: return
        val idx = timeline.measureIndexAt(currentMs)
        _chordCarousel.value = ChordCarouselState(
            previous = timeline.measures.getOrNull(idx - 1)?.chord,
            current = timeline.measures.getOrNull(idx)?.chord,
            next = timeline.measures.getOrNull(idx + 1)?.chord,
            measureIndex = idx
        )
    }

    /**
     * Carousel update for live/streaming paths with no precomputed timeline: "next" can't
     * be known ahead of time, so that slot stays empty. "Previous" tracks whatever the
     * carousel's "current" chord was right before this new one appeared.
     */
    private fun updateLiveCarousel(newChord: ChordInfo?) {
        val prevState = _chordCarousel.value
        if (newChord != null && newChord.name != prevState.current?.name) {
            _chordCarousel.value = ChordCarouselState(
                previous = prevState.current,
                current = newChord,
                next = null,
                measureIndex = prevState.measureIndex + 1
            )
        } else if (newChord != null && prevState.current == null) {
            _chordCarousel.value = prevState.copy(current = newChord)
        }
    }

    private fun maybeRecordHistoryEntry(result: DetectionResult, sourceLabel: String) {
        if (result.status == DetectionStatus.DETECTED && result.chord != null && result.confidence >= 0.45f) {
            val chordName = result.chord.name
            if (chordName != lastRecordedChordName) {
                lastRecordedChordName = chordName
                addToHistory(
                    ChordHistoryItem(
                        chordName = chordName,
                        timestampFormatted = timeFormat.format(Date()),
                        confidencePercent = (result.confidence * 100).toInt(),
                        inputSource = sourceLabel,
                        notes = result.chord.notes
                    )
                )
            }
        }
    }

    private fun addToHistory(item: ChordHistoryItem) {
        val currentList = _chordHistory.value.toMutableList()
        currentList.add(0, item)
        if (currentList.size > 30) {
            currentList.removeAt(currentList.lastIndex)
        }
        _chordHistory.value = currentList
    }

    fun playSyntheticGuitarChord(chordName: String) {
        syntheticJob?.cancel()
        _syntheticChordPlaying.value = chordName

        syntheticJob = viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val freqs = resolveDynamicChordFrequencies(chordName)

            val durationSec = 1.35
            val totalSamples = (sampleRate * durationSec).toInt()
            val pcmBuffer = ShortArray(totalSamples)

            val strumDelaySec = 0.012 // 12ms acoustic strum effect per string
            val attackSec = 0.008     // 8ms smooth fade-in attack to eliminate clicks/scratches
            val fadeOutSamples = 1500 // ~34ms smooth end fade-out

            for (i in 0 until totalSamples) {
                var mix = 0.0
                var activeStrings = 0

                for (fIdx in freqs.indices) {
                    val freq = freqs[fIdx]
                    val stringStartSample = (fIdx * strumDelaySec * sampleRate).toInt()

                    if (i >= stringStartSample) {
                        activeStrings++
                        val t = (i - stringStartSample) / sampleRate.toDouble()

                        // Smooth Attack Fade-In Envelope
                        val attack = (t / attackSec).coerceAtMost(1.0)
                        // Natural Acoustic Pluck Decay
                        val decay = Math.exp(-2.2 * t)
                        val envelope = attack * decay

                        val phase = 2.0 * PI * freq * t
                        val harmonicWave = 0.60 * sin(phase) +
                                0.25 * sin(2.0 * phase) +
                                0.10 * sin(3.0 * phase) +
                                0.05 * sin(4.0 * phase)

                        mix += harmonicWave * envelope
                    }
                }

                if (activeStrings > 0) {
                    mix /= freqs.size
                }

                // Global end fade-out to prevent truncation pops
                if (i > totalSamples - fadeOutSamples) {
                    val fadeFactor = (totalSamples - i).toDouble() / fadeOutSamples
                    mix *= fadeFactor
                }

                pcmBuffer[i] = (mix * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
            }

            var audioTrack: android.media.AudioTrack? = null
            try {
                val bufferSizeBytes = pcmBuffer.size * 2
                audioTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(pcmBuffer, 0, pcmBuffer.size)
                audioTrack.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Feed chunks to UI detector for live visualizer updates
            val chunkSize = 2048
            var offset = 0
            while (offset + chunkSize <= totalSamples && isActive) {
                val chunk = pcmBuffer.copyOfRange(offset, offset + chunkSize)
                withContext(Dispatchers.Main) {
                    val result = detector.processPcmSamples(chunk)
                    _detectionResult.value = result
                    updateLiveCarousel(result.chord)
                }
                offset += chunkSize
                delay(45L)
            }

            delay(300L)
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                _syntheticChordPlaying.value = null
            }
        }
    }

    private fun resolveDynamicChordFrequencies(chordName: String): FloatArray {
        val rootNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val altNames = mapOf("Db" to "C#", "Eb" to "D#", "Gb" to "F#", "Ab" to "G#", "Bb" to "A#")

        var clean = chordName.trim()
        altNames.forEach { (alt, canonical) ->
            if (clean.startsWith(alt)) {
                clean = canonical + clean.removePrefix(alt)
            }
        }

        var rootName = "C"
        for (r in rootNames.sortedByDescending { it.length }) {
            if (clean.startsWith(r)) {
                rootName = r
                break
            }
        }

        val rootIdx = rootNames.indexOf(rootName).coerceAtLeast(0)
        val quality = clean.removePrefix(rootName)

        val rootMidi = 48 + rootIdx // C3 range for guitar voicing

        val intervals = when {
            quality.contains("m") && !quality.contains("maj") -> intArrayOf(0, 3, 7, 12) // Minor
            quality.contains("7") -> intArrayOf(0, 4, 7, 10)                           // Dominant 7th
            quality.contains("dim") -> intArrayOf(0, 3, 6, 9)                           // Diminished
            quality.contains("sus") -> intArrayOf(0, 5, 7, 12)                          // Sus4
            quality.contains("5") -> intArrayOf(0, 7, 12)                               // Power chord
            else -> intArrayOf(0, 4, 7, 12)                                             // Major
        }

        return intervals.map { interval ->
            val midi = rootMidi + interval
            (440.0 * Math.pow(2.0, (midi - 69) / 12.0)).toFloat()
        }.toFloatArray()
    }

    fun clearHistory() {
        _chordHistory.value = emptyList()
    }

    // --- Vocalist Key Assistant Methods ---

    fun startVocalRecording() {
        stopAllProcessing()
        vocalPcmBuffers.clear()
        _vocalAnalysisResult.value = null
        _vocalRecordingTimeSec.value = 0
        _isRecordingVocal.value = true

        // Timer job
        vocalTimerJob?.cancel()
        vocalTimerJob = viewModelScope.launch {
            while (_isRecordingVocal.value) {
                delay(1000L)
                val newTime = _vocalRecordingTimeSec.value + 1
                _vocalRecordingTimeSec.value = newTime
                if (newTime >= 15) {
                    stopVocalRecordingAndAnalyze()
                    break
                }
            }
        }

        micRecorder.startRecording(
            scope = viewModelScope,
            bufferSizeSamples = 2048,
            onPcmBuffer = { pcmChunk ->
                if (_isRecordingVocal.value) {
                    vocalPcmBuffers.add(pcmChunk)
                }
            },
            onError = {
                _isRecordingVocal.value = false
                vocalTimerJob?.cancel()
            }
        )
    }

    fun stopVocalRecordingAndAnalyze() {
        if (!_isRecordingVocal.value && vocalPcmBuffers.isEmpty()) return

        _isRecordingVocal.value = false
        vocalTimerJob?.cancel()
        micRecorder.stopRecording()

        _isAnalyzingVocal.value = true

        viewModelScope.launch(Dispatchers.Default) {
            val result = vocalAnalyzer.analyzeSingingSample(vocalPcmBuffers)
            withContext(Dispatchers.Main) {
                _vocalAnalysisResult.value = result
                _isAnalyzingVocal.value = false
            }
        }
    }

    fun updateVocalTranspositionShift(shift: Int) {
        val current = _vocalAnalysisResult.value ?: return
        if (vocalPcmBuffers.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            val updated = vocalAnalyzer.analyzeSingingSample(
                pcmBuffers = vocalPcmBuffers,
                manualTranspositionOffset = shift.coerceIn(-6, 6)
            )
            withContext(Dispatchers.Main) {
                _vocalAnalysisResult.value = updated
            }
        }
    }

    fun playProgression(progression: VocalProgression) {
        progressionJob?.cancel()
        _activeProgressionTitle.value = progression.title

        progressionJob = viewModelScope.launch {
            for (chordName in progression.chordNames) {
                playSyntheticGuitarChord(chordName)
                delay(1400L)
            }
            _activeProgressionTitle.value = null
        }
    }

    fun resetVocalAnalysis() {
        vocalTimerJob?.cancel()
        progressionJob?.cancel()
        _isRecordingVocal.value = false
        _isAnalyzingVocal.value = false
        _vocalAnalysisResult.value = null
        _activeProgressionTitle.value = null
        vocalPcmBuffers.clear()
        stopAllProcessing()
    }

    private fun stopAllProcessing() {
        micRecorder.stopRecording()
        fileProcessor.stopPlayback()
        youtubeExtractor.stopStreaming()
        syntheticJob?.cancel()
        _syntheticChordPlaying.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAllProcessing()
        analysisJob?.cancel()
    }
}