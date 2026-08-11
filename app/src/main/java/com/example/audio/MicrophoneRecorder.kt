package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MicrophoneRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Single-threaded (but thread-pool-backed) dispatcher used to deliver PCM chunks to the
    // caller's callback. Using limitedParallelism(1) on Dispatchers.Default instead of
    // Dispatchers.Main means:
    //  - Callback delivery -- and any downstream chord/pitch detection the caller does inside
    //    it (a ~16k-point FFT per chunk in ChromaChordDetector) -- never runs on the main
    //    thread, so it can no longer cause UI jank or dropped frames.
    //  - Chunks are still delivered strictly one-at-a-time, in order, matching the previous
    //    Dispatchers.Main behavior. This matters because ChromaChordDetector holds mutable
    //    per-session state (rolling analysis buffer, per-measure vote tally, key-stability
    //    tracking) that is only safe to mutate from one thread at a time -- plain
    //    Dispatchers.Default would let multiple chunks run concurrently on different threads
    //    and could corrupt that state.
    private val callbackDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        bufferSizeSamples: Int = 2048,
        onPcmBuffer: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!hasPermission()) {
            onError("Microphone permission not granted")
            return
        }

        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError("Audio hardware not supported or buffer size error")
            return
        }

        val actualBufferSize = (minBufferSize * 2).coerceAtLeast(bufferSizeSamples * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("Failed to initialize AudioRecord device")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSizeSamples)
                while (isActive && isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readSize > 0) {
                        val chunk = buffer.copyOf(readSize)
                        // Fire-and-forget onto callbackDispatcher (off the main thread, but
                        // still serialized) so heavier chord-detection processing downstream
                        // can never throttle how fast we drain the AudioRecord buffer --
                        // otherwise mic reads back up and real-time listening gets laggy --
                        // and so that processing no longer competes with UI rendering on the
                        // main thread.
                        scope.launch(callbackDispatcher) {
                            onPcmBuffer(chunk)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            isRecording = false
            onError("Microphone error: ${e.localizedMessage}")
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore release exceptions
        } finally {
            audioRecord = null
        }
    }
}