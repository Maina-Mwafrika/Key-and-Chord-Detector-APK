package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FileProcessor(private val context: Context) {

    private var playbackJob: Job? = null
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null

    fun getFileMetadata(uri: Uri): AudioFileMetadata? {
        return try {
            var fileName = "Audio File"
            var fileSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "Audio File"
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }

            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var durationUs = 0L

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
            extractor.release()

            val sizeKb = fileSize / 1024
            val sizeFormatted = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB"

            AudioFileMetadata(
                fileName = fileName,
                durationMs = durationUs / 1000,
                fileSizeFormatted = sizeFormatted,
                uriString = uri.toString()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun processAndPlayAudioUri(
        scope: CoroutineScope,
        uri: Uri,
        detector: ChromaChordDetector,
        onProgressUpdate: (currentMs: Long, totalMs: Long) -> Unit,
        onPcmChunk: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ) {
        stopPlayback()
        isPlaying = true

        playbackJob = scope.launch(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null

            try {
                extractor.setDataSource(context, uri, null)
                var audioTrackIdx = -1
                var format: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val trFormat = extractor.getTrackFormat(i)
                    val mime = trFormat.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIdx = i
                        format = trFormat
                        break
                    }
                }

                if (audioTrackIdx < 0 || format == null) {
                    withContext(Dispatchers.Main) {
                        onError("Unsupported audio track format")
                    }
                    extractor.release()
                    return@launch
                }

                extractor.selectTrack(audioTrackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100

                // IMPORTANT: most audio files are stereo. MediaCodec decodes to interleaved
                // L,R,L,R... 16-bit PCM. Previously this was blindly treated as mono, which
                // packed 2 samples' worth of playback time into every "1 mono sample" slot --
                // that plays the file back at roughly half speed (lower pitch, stretched/
                // delayed sound). We downmix to true mono below so both AudioTrack playback
                // and the chord detector see a correctly-paced, single-channel signal.
                val channelCount = (if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1).coerceAtLeast(1)

                // Initialize AudioTrack for real-time sound output (always mono output,
                // since we downmix before writing)
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
                        // Slightly larger buffer gives extra headroom so chord-detection
                        // processing on the callback thread can never starve playback.
                        .setBufferSizeInBytes(maxOf(minBufSize * 2, 4096 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack?.play()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                val chunkSize = 2048
                val shortBuffer = ShortArray(chunkSize)
                var bufferIdx = 0

                while (isActive && isPlaying && !sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inputBufIdx = codec.dequeueInputBuffer(10000)
                        if (inputBufIdx >= 0) {
                            val inputBuf = codec.getInputBuffer(inputBufIdx)
                            if (inputBuf != null) {
                                val sampleSize = extractor.readSampleData(inputBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inputBufIdx, 0, sampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputBufIdx = codec.dequeueOutputBuffer(info, 10000)
                    if (outputBufIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outputBufIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)

                            val pcmBytes = ByteArray(info.size)
                            outBuf.get(pcmBytes)

                            // Convert 16-bit PCM bytes to Shorts, downmixing to mono if the
                            // source has more than one channel (averaging all channels per
                            // frame) so playback speed/pitch and detector input stay correct.
                            val rawShortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val monoSamples: ShortArray = if (channelCount > 1) {
                                val frameCount = rawShortBuf.remaining() / channelCount
                                ShortArray(frameCount) {
                                    var sum = 0
                                    for (c in 0 until channelCount) {
                                        sum += rawShortBuf.get()
                                    }
                                    (sum / channelCount).toShort()
                                }
                            } else {
                                ShortArray(rawShortBuf.remaining()) { rawShortBuf.get() }
                            }

                            for (sample in monoSamples) {
                                shortBuffer[bufferIdx++] = sample
                                if (bufferIdx >= chunkSize) {
                                    val chunk = shortBuffer.copyOf()
                                    val currentMs = info.presentationTimeUs / 1000
                                    val totalMs = durationUs / 1000

                                    // Play chunk via AudioTrack (blocking write paces playback speed accurately)
                                    val byteChunk = ByteArray(chunkSize * 2)
                                    ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk)
                                    audioTrack?.write(byteChunk, 0, byteChunk.size)

                                    // Fire-and-forget the UI/detection callback instead of
                                    // suspending on it. Chord detection now does a heavier
                                    // FFT; blocking this decode loop on it let playback fall
                                    // behind real time and stutter/distort. The audio has
                                    // already been queued to AudioTrack above, so playback
                                    // timing no longer depends on how long detection takes.
                                    scope.launch(Dispatchers.Main) {
                                        onPcmChunk(chunk)
                                        onProgressUpdate(currentMs, totalMs)
                                    }
                                    bufferIdx = 0
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputBufIdx, false)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Error decoding audio file: ${e.localizedMessage}")
                }
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                } catch (e: Exception) {}
                try {
                    extractor.release()
                } catch (e: Exception) {}
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {}
                audioTrack = null
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }
}