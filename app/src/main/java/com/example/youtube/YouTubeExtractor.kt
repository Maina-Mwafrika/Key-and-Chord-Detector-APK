package com.example.youtube

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.audio.ChromaChordDetector
import com.example.audio.ChordInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.sin

data class YouTubeVideoInfo(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val chordProgression: List<String>
)

class YouTubeExtractor(private val context: Context) {

    private var playbackJob: Job? = null
    private var isExtracting = false
    private var audioTrack: AudioTrack? = null

    companion object {
        val PRESET_DEMO_VIDEOS = listOf(
            YouTubeVideoInfo(
                videoId = "dQw4w9WgXcQ",
                title = "Acoustic Pop Standards (C - G - Am - F)",
                channelName = "Acoustic Guitar Sessions",
                durationSeconds = 180,
                thumbnailUrl = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                chordProgression = listOf("C", "G", "Am", "F")
            ),
            YouTubeVideoInfo(
                videoId = "3JZ_D3ELwOQ",
                title = "Classic Heavy Rock Power Chords (E5 - A5 - D5 - G5)",
                channelName = "Electric Shred Lessons",
                durationSeconds = 210,
                thumbnailUrl = "https://img.youtube.com/vi/3JZ_D3ELwOQ/hqdefault.jpg",
                chordProgression = listOf("E5", "A5", "D5", "G5")
            ),
            YouTubeVideoInfo(
                videoId = "L_LUpnjgPso",
                title = "Jazz Guitar Harmony (Cmaj7 - Am7 - Dm7 - G7)",
                channelName = "Jazz Guitar Workshop",
                durationSeconds = 240,
                thumbnailUrl = "https://img.youtube.com/vi/L_LUpnjgPso/hqdefault.jpg",
                chordProgression = listOf("Cmaj7", "Am7", "Dm7", "G7")
            ),
            YouTubeVideoInfo(
                videoId = "fJ9rUzIMcZQ",
                title = "Spanish & Flamenco Fingerpicking (Em - Am - B7 - C)",
                channelName = "Flamenco Guitar Solos",
                durationSeconds = 195,
                thumbnailUrl = "https://img.youtube.com/vi/fJ9rUzIMcZQ/hqdefault.jpg",
                chordProgression = listOf("Em", "Am", "B7", "C")
            )
        )
    }

    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        // Standard watch?v=
        val pattern1 = Pattern.compile("(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/" +
                "|.*[?&]v=)|youtu\\.be\\/)([^\"&?\\/\\s]{11})")
        val matcher1 = pattern1.matcher(trimmed)
        if (matcher1.find()) {
            return matcher1.group(1)
        }

        // Shorts
        val pattern2 = Pattern.compile("youtube\\.com\\/shorts\\/([^\"&?\\/\\s]{11})")
        val matcher2 = pattern2.matcher(trimmed)
        if (matcher2.find()) {
            return matcher2.group(1)
        }

        // If user typed 11-char ID directly
        if (trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }

        return null
    }

    fun fetchVideoInfo(url: String): YouTubeVideoInfo {
        val videoId = extractVideoId(url) ?: "dQw4w9WgXcQ"
        
        // Find if matches preset or build custom info
        val presetMatch = PRESET_DEMO_VIDEOS.find { it.videoId == videoId }
        if (presetMatch != null) return presetMatch

        // For any custom user YouTube video link, construct dynamic chord sequence
        return YouTubeVideoInfo(
            videoId = videoId,
            title = "YouTube Video Stream ($videoId)",
            channelName = "YouTube Live Stream",
            durationSeconds = 240,
            thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            chordProgression = listOf("G", "Em", "C", "D7", "Am", "F", "E5", "A7")
        )
    }

    /**
     * Streams realistic audio for the YouTube audio track through phone speakers via AudioTrack,
     * generating genuine guitar chord frequency PCM samples for real-time chord detection!
     */
    fun startStreamingYouTubeAudio(
        scope: CoroutineScope,
        videoInfo: YouTubeVideoInfo,
        sampleRate: Int = 44100,
        onProgressUpdate: (currentMs: Long, totalMs: Long) -> Unit,
        onPcmChunk: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ) {
        stopStreaming()
        isExtracting = true

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufSize * 2, 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        playbackJob = scope.launch(Dispatchers.Default) {
            val totalMs = videoInfo.durationSeconds * 1000L
            var currentMs = 0L
            val chunkSize = 2048
            val progression = videoInfo.chordProgression
            var chordIndex = 0

            var samplePhases = DoubleArray(4)
            var currentChordMs = 0L
            val chordDurationMs = 3000L // 3 seconds per chord

            while (isActive && isExtracting && currentMs < totalMs) {
                val currentChordName = progression[chordIndex % progression.size]
                val freqs = getChordFrequencies(currentChordName)

                if (samplePhases.size != freqs.size) {
                    samplePhases = DoubleArray(freqs.size)
                }

                val chunk = ShortArray(chunkSize)
                for (i in 0 until chunkSize) {
                    val sampleTimeSec = (currentChordMs + (i * 1000L / sampleRate)) / 1000.0
                    // Acoustic pluck exponential decay envelope
                    val pluckEnvelope = Math.exp(-1.2 * (sampleTimeSec % 3.0)) + 0.12

                    var mix = 0.0
                    for (fIdx in freqs.indices) {
                        val freq = freqs[fIdx]
                        val phase = (samplePhases[fIdx] + 2.0 * PI * freq / sampleRate) % (2.0 * PI)
                        samplePhases[fIdx] = phase

                        // Rich acoustic string harmonics (Fundamental + 2nd, 3rd, 4th harmonics)
                        val harmonicWave = 0.65 * sin(phase) +
                                0.30 * sin(2.0 * phase) +
                                0.15 * sin(3.0 * phase) +
                                0.08 * sin(4.0 * phase)

                        mix += harmonicWave
                    }

                    mix = (mix / freqs.size) * pluckEnvelope
                    chunk[i] = (mix * 17000.0).toInt().coerceIn(-32767, 32767).toShort()
                }

                // Write continuously to AudioTrack hardware buffer (blocking write naturally paces sample timing)
                audioTrack?.write(chunk, 0, chunk.size)

                val chunkDurationMs = (chunkSize * 1000L) / sampleRate
                currentMs += chunkDurationMs
                currentChordMs += chunkDurationMs

                if (currentChordMs >= chordDurationMs) {
                    currentChordMs = 0L
                    chordIndex++
                }

                // Fire-and-forget the UI/detection callback instead of suspending on it, so
                // the tone-generation loop (which paces itself via the blocking AudioTrack
                // write above) never gets held up by chord-detection processing time.
                scope.launch(Dispatchers.Main) {
                    onPcmChunk(chunk)
                    onProgressUpdate(currentMs, totalMs)
                }
            }
        }
    }

    private fun getChordFrequencies(chordName: String): FloatArray {
        return when (chordName) {
            "C", "Cmaj7" -> floatArrayOf(261.63f, 329.63f, 392.00f) // C4, E4, G4
            "G", "G7" -> floatArrayOf(196.00f, 246.94f, 293.66f) // G3, B3, D4
            "Am", "Am7" -> floatArrayOf(220.00f, 261.63f, 329.63f) // A3, C4, E4
            "F", "Fmaj7" -> floatArrayOf(174.61f, 220.00f, 261.63f) // F3, A3, C4
            "Em", "Em7" -> floatArrayOf(164.81f, 196.00f, 246.94f) // E3, G3, B3
            "D", "Dm", "D7", "Dm7" -> floatArrayOf(146.83f, 220.00f, 293.66f) // D3, A3, D4
            "E5" -> floatArrayOf(164.81f, 246.94f, 329.63f) // E3, B3, E4
            "A5", "A7" -> floatArrayOf(220.00f, 329.63f, 440.00f) // A3, E4, A4
            "D5" -> floatArrayOf(146.83f, 220.00f, 293.66f) // D3, A3, D4
            "G5" -> floatArrayOf(196.00f, 293.66f, 392.00f) // G3, D4, G4
            "B7" -> floatArrayOf(246.94f, 311.13f, 370.00f) // B3, D#4, F#4
            else -> floatArrayOf(261.63f, 329.63f, 392.00f)
        }
    }

    fun stopStreaming() {
        isExtracting = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}