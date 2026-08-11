package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Performs a one-time, offline (non-real-time) analysis of an uploaded song: decodes the
 * whole file into memory, determines a single overall key for the entire song, and works
 * out the best-fitting chord for every measure. The result (SongChordTimeline) can then be
 * played back against with zero per-frame detection jitter -- chord and key are already
 * known ahead of time, so the UI can render them perfectly smoothly and even show upcoming
 * chords before they happen (previous/current/next carousel).
 */
class SongAnalyzer(private val context: Context) {

    data class Progress(val fraction: Float, val stage: String)

    /**
     * This app has no automatic tempo/beat detection, so measure length is assumed from
     * [bpm]/[beatsPerMeasure] (default 120bpm 4/4 = 2s/measure). Pass real values if the
     * song's actual tempo is known, for a tighter chord-to-bar sync.
     */
    suspend fun analyze(
        uri: Uri,
        chordMode: ChordComplexityMode,
        bpm: Int = 120,
        beatsPerMeasure: Int = 4,
        onProgress: (Progress) -> Unit = {}
    ): SongChordTimeline = withContext(Dispatchers.Default) {
        onProgress(Progress(0.05f, "Decoding audio"))
        val decoded = decodeToMonoPcm(uri)

        val detector = ChromaChordDetector(sampleRate = decoded.sampleRate)
        val windowSize = ChromaChordDetector.ANALYSIS_WINDOW_SIZE
        val hopSize = windowSize / 2 // 50% overlap (reduces total FFT hops by 2x while maintaining high accuracy)

        // --- Pass 1: Parallel whole-song chroma extraction across CPU cores ---
        onProgress(Progress(0.20f, "Analyzing key & chroma"))
        val globalChroma = FloatArray(12)

        val samples = decoded.samples
        val totalHops = (((samples.size - windowSize).coerceAtLeast(0) / hopSize) + 1).coerceAtLeast(1)

        val numCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val hopsPerWorker = (totalHops + numCores - 1) / numCores
        val chunks = (0 until numCores).map { core ->
            val start = core * hopsPerWorker
            val end = (start + hopsPerWorker).coerceAtMost(totalHops)
            start to end
        }.filter { it.first < it.second }

        val completedHops = AtomicInteger(0)

        val workerResults = coroutineScope {
            chunks.map { (startHop, endHop) ->
                async(Dispatchers.Default) {
                    val localDetector = ChromaChordDetector(sampleRate = decoded.sampleRate)
                    // Triple = (timestampMs, chroma, bassPitchClass). The bass pitch class is
                    // the loudest pitch class in the guitar's low register for this window --
                    // carrying it through to Pass 2 lets matchChord anchor each measure's
                    // chord root to what the bass is actually doing, instead of relying only
                    // on overall chroma-template similarity (which can be fooled by a single
                    // loud note that happens to belong to a different chord).
                    val list = ArrayList<Triple<Long, FloatArray, Int>>(endHop - startHop)
                    for (hop in startHop until endHop) {
                        val pos = hop * hopSize
                        if (pos + windowSize <= samples.size) {
                            // Zero-copy window analysis with cheap RMS skip & in-place FFT
                            val (chroma, bassPitchClass) = localDetector.analyzeChromaWithBass(samples, pos, windowSize)
                            val timestampMs = (pos.toLong() * 1000L) / decoded.sampleRate
                            list.add(Triple(timestampMs, chroma, bassPitchClass))
                        }
                        val done = completedHops.incrementAndGet()
                        if (done % 30 == 0 || done == totalHops) {
                            val pFrac = 0.20f + 0.58f * (done.toFloat() / totalHops.toFloat().coerceAtLeast(1f))
                            onProgress(Progress(pFrac, "Analyzing key & chroma"))
                        }
                    }
                    list
                }
            }.awaitAll()
        }

        val hopChromas = ArrayList<Triple<Long, FloatArray, Int>>(totalHops)
        for (list in workerResults) {
            for ((timestampMs, chroma, bassPitchClass) in list) {
                hopChromas.add(Triple(timestampMs, chroma, bassPitchClass))
                for (i in 0 until 12) {
                    globalChroma[i] += chroma[i]
                }
            }
        }

        val overallKey = detector.estimateKeyFromChroma(normalizeVectorSafe(globalChroma))

        // --- Determine candidate chord set based on complexity mode ---
        onProgress(Progress(0.79f, "Selecting chord palette"))
        val candidateChords = when (chordMode) {
            ChordComplexityMode.SIMPLE -> {
                val diatonicNames = diatonicChordNamesForKey(overallKey)
                detector.chordDatabase.filter { it.name in diatonicNames }
            }
            ChordComplexityMode.ADVANCED -> detector.chordDatabase
        }.ifEmpty { detector.chordDatabase }

        // Soft in-key bias for Advanced mode only: Advanced still allows ANY chord (including
        // borrowed/chromatic ones) to win a measure, but a marginal/ambiguous hop should lean
        // toward the song's own key rather than flipping to an unrelated out-of-key chord just
        // because of one noisy frame. This is a mild nudge applied to the vote weight below,
        // not a restriction on candidates -- a genuinely well-matched borrowed chord still
        // wins comfortably against it.
        val inKeyBiasNames = if (chordMode == ChordComplexityMode.ADVANCED) {
            diatonicChordNamesForKey(overallKey)
        } else {
            emptySet()
        }

        // --- Pass 2: group hops into measures and vote for the best-fitting chord with smooth progress ---
        onProgress(Progress(0.80f, "Mapping chords to measures"))
        val measureDurationMs = ((60000f / bpm) * beatsPerMeasure).toLong().coerceAtLeast(250L)
        val totalDurationMs = (samples.size.toLong() * 1000L) / decoded.sampleRate
        val measureCount = ((totalDurationMs / measureDurationMs) + 1).toInt().coerceAtLeast(1)

        val votesPerMeasure = Array(measureCount) { HashMap<String?, Pair<Float, Int>>() }

        val totalHopsInList = hopChromas.size.coerceAtLeast(1)
        for ((idx, triple) in hopChromas.withIndex()) {
            val (timestampMs, chroma, bassPitchClass) = triple
            val measureIdx = (timestampMs / measureDurationMs).toInt().coerceIn(0, measureCount - 1)
            val (chord, rawConfidence) = detector.matchChord(chroma, candidateChords, bassPitchClass)

            val biasedConfidence = if (chord != null && chord.name in inKeyBiasNames) {
                (rawConfidence * 1.08f).coerceAtMost(1f)
            } else {
                rawConfidence
            }

            if (biasedConfidence > 0.15f) {
                val voteKey = chord?.name
                val prev = votesPerMeasure[measureIdx][voteKey] ?: (0f to 0)
                // Square the confidence weight so a handful of clean, complete-chord matches
                // decisively outvote many noisy/partial-match hops instead of being averaged
                // together with them -- this is what keeps a measure's displayed chord from
                // flip-flopping between several plausible candidates due to a few ambiguous
                // frames within that bar.
                val weighted = biasedConfidence * biasedConfidence
                votesPerMeasure[measureIdx][voteKey] = (prev.first + weighted) to (prev.second + 1)
            }
            if (idx % 100 == 0 || idx == totalHopsInList - 1) {
                val pFrac = 0.80f + 0.18f * ((idx + 1).toFloat() / totalHopsInList.toFloat())
                onProgress(Progress(pFrac, "Mapping chords to measures"))
            }
        }

        val measures = (0 until measureCount).map { idx ->
            val votes = votesPerMeasure[idx]
            val winner = votes.maxByOrNull { it.value.first }
            val winnerName = winner?.key
            val chord = winnerName?.let { name -> candidateChords.find { it.name == name } }
            // Votes were accumulated as confidence^2 (see weighting above); take the square
            // root back to restore a genuine 0..1 confidence value for display/history.
            val confidence = winner?.let {
                if (it.value.second > 0) sqrt((it.value.first / it.value.second).toDouble()).toFloat() else 0f
            } ?: 0f

            MeasureChord(
                measureIndex = idx,
                startMs = idx * measureDurationMs,
                endMs = (idx + 1) * measureDurationMs,
                chord = chord,
                confidence = confidence
            )
        }

        onProgress(Progress(1.0f, "Done"))

        SongChordTimeline(
            key = overallKey,
            bpm = bpm,
            beatsPerMeasure = beatsPerMeasure,
            measureDurationMs = measureDurationMs,
            measures = measures,
            totalDurationMs = totalDurationMs,
            chordMode = chordMode
        )
    }

    private fun normalizeVectorSafe(v: FloatArray): FloatArray {
        var maxVal = 0f
        for (x in v) if (x > maxVal) maxVal = x
        if (maxVal == 0f) return FloatArray(v.size)
        return FloatArray(v.size) { v[it] / maxVal }
    }

    /**
     * Returns the chord-database name strings (e.g. "C", "Dm", "Bdim") for the 7 diatonic
     * triads/7ths of the given key. Used to constrain "Simple" mode matching to chords that
     * actually belong to the song's key, and to compute the soft in-key bias for "Advanced"
     * mode above.
     */
    private fun diatonicChordNamesForKey(keyName: String): Set<String> {
        val isMinor = keyName.contains("Minor")
        val rootName = keyName
            .substringAfter("Key of ")
            .substringBefore(if (isMinor) "m (Minor)" else " Major")
            .trim()

        val rootIdx = ChromaChordDetector.NOTE_NAMES.indexOf(rootName)
        if (rootIdx < 0) return emptySet()

        fun noteAt(semitones: Int) = ChromaChordDetector.NOTE_NAMES[(rootIdx + semitones + 12) % 12]

        return if (!isMinor) {
            // Major scale diatonic harmonization: I ii iii IV V vi vii°
            setOf(
                noteAt(0),          // I major
                "${noteAt(2)}m",    // ii minor
                "${noteAt(4)}m",    // iii minor
                noteAt(5),          // IV major
                noteAt(7),          // V major
                "${noteAt(9)}m",    // vi minor
                "${noteAt(11)}dim"  // vii diminished
            )
        } else {
            // Natural minor diatonic harmonization: i ii° III iv v VI VII
            setOf(
                "${noteAt(0)}m",    // i minor
                "${noteAt(2)}dim",  // ii diminished
                noteAt(3),          // III major
                "${noteAt(5)}m",    // iv minor
                "${noteAt(7)}m",    // v minor
                noteAt(8),          // VI major
                noteAt(10)          // VII major
            )
        }
    }

    private data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    /**
     * Decodes the entire audio file to a single mono FloatArray as fast as possible (no
     * real-time pacing, no AudioTrack playback -- this is a headless, one-shot decode used
     * only for analysis). Downmixes multi-channel audio the same way FileProcessor's
     * real-time playback path does, so analysis and playback agree on timing/pitch.
     */
    private fun decodeToMonoPcm(uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val outputChunks = mutableListOf<ShortArray>()
        var totalSamples = 0
        var sampleRate = 44100

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
                return DecodedAudio(FloatArray(0), sampleRate)
            }

            extractor.selectTrack(audioTrackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = (if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
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

                        val rawShortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val monoSamples: ShortArray = if (channelCount > 1) {
                            val frameCount = rawShortBuf.remaining() / channelCount
                            ShortArray(frameCount) {
                                var sum = 0
                                for (c in 0 until channelCount) sum += rawShortBuf.get()
                                (sum / channelCount).toShort()
                            }
                        } else {
                            ShortArray(rawShortBuf.remaining()) { rawShortBuf.get() }
                        }

                        outputChunks.add(monoSamples)
                        totalSamples += monoSamples.size
                    }

                    codec.releaseOutputBuffer(outputBufIdx, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
            }
            try {
                extractor.release()
            } catch (e: Exception) {
            }
        }

        val floatSamples = FloatArray(totalSamples)
        var offset = 0
        for (chunk in outputChunks) {
            for (s in chunk) {
                floatSamples[offset++] = s / 32768.0f
            }
        }

        return DecodedAudio(floatSamples, sampleRate)
    }
}