package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFileMetadata
import com.example.audio.ChordHistoryItem
import com.example.audio.ChromaChordDetector
import com.example.audio.DetectionInputMode
import com.example.audio.DetectionResult
import com.example.audio.DetectionStatus
import com.example.audio.FileProcessor
import com.example.audio.MicrophoneRecorder
import com.example.youtube.YouTubeExtractor
import com.example.youtube.YouTubeVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastRecordedChordName: String? = null
    private var syntheticJob: Job? = null

    fun setInputMode(mode: DetectionInputMode) {
        stopAllProcessing()
        detector.resetState()
        _inputMode.value = mode
        _detectionResult.value = DetectionResult(status = DetectionStatus.IDLE)
        _status.value = DetectionStatus.IDLE
        _isPlaying.value = false
    }

    fun hasMicPermission(): Boolean {
        return micRecorder.hasPermission()
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
    }

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

                fileProcessor.processAndPlayAudioUri(
                    scope = viewModelScope,
                    uri = uri,
                    detector = detector,
                    onProgressUpdate = { currentMs, totalMs ->
                        _playbackProgressMs.value = currentMs
                        _totalDurationMs.value = totalMs
                    },
                    onPcmChunk = { chunk ->
                        processPcmBuffer(chunk, sourceLabel = _fileMetadata.value?.fileName ?: "Audio File")
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

    fun pausePlayback() {
        stopAllProcessing()
        _isPlaying.value = false
        _status.value = DetectionStatus.IDLE
    }

    private fun processPcmBuffer(pcmChunk: ShortArray, sourceLabel: String) {
        val result = detector.processPcmSamples(pcmChunk)
        _detectionResult.value = result

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
            val freqs = when (chordName) {
                "C" -> floatArrayOf(261.63f, 329.63f, 392.00f)
                "G" -> floatArrayOf(196.00f, 246.94f, 293.66f)
                "Am" -> floatArrayOf(220.00f, 261.63f, 329.63f)
                "F" -> floatArrayOf(174.61f, 220.00f, 261.63f)
                "Em" -> floatArrayOf(164.81f, 196.00f, 246.94f)
                "D7" -> floatArrayOf(146.83f, 220.00f, 293.66f)
                "E5" -> floatArrayOf(164.81f, 246.94f, 329.63f)
                else -> floatArrayOf(261.63f, 329.63f, 392.00f)
            }

            var audioTrack: android.media.AudioTrack? = null
            try {
                val minBuf = android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
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
                    .setBufferSizeInBytes(maxOf(minBuf, 2048 * 4))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                audioTrack.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val chunkSize = 2048
            val chunk = ShortArray(chunkSize)
            val phases = DoubleArray(freqs.size)
            var currentMs = 0L

            for (repeat in 0 until 40) { // ~1.8 seconds audio
                for (i in 0 until chunkSize) {
                    val sampleTimeSec = (currentMs + (i * 1000L / sampleRate)) / 1000.0
                    // Pluck decay envelope
                    val pluckEnvelope = Math.exp(-1.5 * sampleTimeSec) + 0.08

                    var mix = 0.0
                    for (fIdx in freqs.indices) {
                        val freq = freqs[fIdx]
                        val phase = (phases[fIdx] + 2.0 * PI * freq / sampleRate) % (2.0 * PI)
                        phases[fIdx] = phase

                        val harmonicWave = 0.65 * sin(phase) +
                                0.30 * sin(2.0 * phase) +
                                0.15 * sin(3.0 * phase)

                        mix += harmonicWave
                    }

                    mix = (mix / freqs.size) * pluckEnvelope
                    chunk[i] = (mix * 18000.0).toInt().coerceIn(-32767, 32767).toShort()
                }

                audioTrack?.write(chunk, 0, chunk.size)
                currentMs += (chunkSize * 1000L) / sampleRate

                withContext(Dispatchers.Main) {
                    val result = detector.processPcmSamples(chunk)
                    _detectionResult.value = result
                }
            }

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                _syntheticChordPlaying.value = null
            }
        }
    }

    fun clearHistory() {
        _chordHistory.value = emptyList()
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
    }
}
