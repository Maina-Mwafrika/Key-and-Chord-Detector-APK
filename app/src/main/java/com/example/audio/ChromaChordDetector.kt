package com.example.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ChromaChordDetector(private val sampleRate: Int = 44100) {

    companion object {
        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        // Guitar string fundamental frequencies in Hz
        val GUITAR_STRING_FREQS = floatArrayOf(
            82.41f,  // 6th string Low E (E2)
            110.00f, // 5th string A (A2)
            146.83f, // 4th string D (D3)
            196.00f, // 3rd string G (G3)
            246.94f, // 2nd string B (B3)
            329.63f  // 1st string High E (E4)
        )

        val GUITAR_STRING_NAMES = arrayOf("E2", "A2", "D3", "G3", "B3", "E4")

        // Frequency resolution = sampleRate / windowSize. A short 2048-sample window gives
        // ~21.5 Hz/bin at 44.1kHz, which can't separate adjacent low notes (e.g. E2 vs F2 are
        // only ~5 Hz apart). 16384 real samples (~371ms @ 44.1kHz) gives ~2.69 Hz/bin. Public
        // so SongAnalyzer's offline windowing stays consistent with the live path.
        const val ANALYSIS_WINDOW_SIZE = 16384

        // Suppresses percussion/cymbal/vocal-sibilance energy above the guitar's useful
        // fundamental+harmonic range so full song mixes don't pollute the chroma vector.
        private const val LOWPASS_CUTOFF_HZ = 2500f

        // --- Key stability tuning ---
        // The key estimate is derived from a slow exponential moving average of the chroma
        // vector, decayed very gradually so it reflects the song's overall tonal center
        // rather than whichever chord happens to be ringing right now. A candidate key must
        // stay different from the current stable key for this many milliseconds of real
        // audio time before it's accepted -- this is what makes the displayed key static for
        // a given song while still allowing genuine section-to-section modulations (a verse
        // that goes up/down a key) to eventually be picked up. (Only used by the live
        // streaming path -- SongAnalyzer determines key once, offline, from the whole song.)
        private const val LONG_TERM_CHROMA_DECAY = 0.999f
        private const val KEY_STABILITY_HOLD_MS = 8000f // ~4 bars @ 120bpm 4/4

        // --- Chord "sustain per measure" tuning (live streaming path only) ---
        private const val MEASURE_VOTE_MIN_CONFIDENCE = 0.15f

        // --- Adaptive noise floor tuning (live streaming path only) ---
        // A single fixed RMS gate is either too strict in a noisy room or too loose (picks up
        // constant mic self-noise/hiss as "notes") on a hot/sensitive mic. Instead, the first
        // NOISE_CALIBRATION_MS of a session is treated as ambient noise and averaged into a
        // floor specific to this device/room; after that, the actual voice-activity gate is a
        // multiple of that floor, clamped to a sane range. See processPcmSamples().
        private const val NOISE_CALIBRATION_MS = 400f
        private const val NOISE_FLOOR_MULTIPLIER = 3f
        private const val MIN_ADAPTIVE_THRESHOLD = 0.004f
        private const val MAX_ADAPTIVE_THRESHOLD = 0.05f

        // --- Chord-match accuracy tuning ---
        // A pitch class counts as an actually-sounding chord tone only once its normalized
        // chroma energy clears this bar. Used by evaluateChordScore's "completeness" check so
        // a single loud bin can't make an unrelated chord look like a good match.
        private const val NOTE_PRESENCE_THRESHOLD = 0.18f
        // How much a chord's score is scaled down when few of its notes are actually present,
        // even if the notes that ARE present line up well. 0 completeness -> this floor;
        // full completeness -> 1.0 (no penalty).
        private const val COMPLETENESS_SCORE_FLOOR = 0.35f
        // Bonus multiplier applied when the chord's root matches the detected bass-register
        // pitch class -- the bass note is a much stronger root indicator than overall energy.
        private const val BASS_ROOT_MATCH_BONUS = 1.2f
        // Bass-register search range (Hz) used to find the "actual" bass note, roughly a
        // guitar's low E through the octave above it.
        private const val BASS_SEARCH_MIN_HZ = 70f
        private const val BASS_SEARCH_MAX_HZ = 260f
    }

    // Public so SongAnalyzer can build filtered candidate lists (e.g. diatonic-only chords
    // for "Simple" mode) from the same chord vocabulary the live detector uses.
    val chordDatabase: List<ChordInfo> = buildChordDatabase()

    private val lastChromaHistory = mutableListOf<FloatArray>()
    private val maxHistoryFrames = 4

    // Rolling buffer that accumulates incoming PCM chunks (mic/file/YouTube callers all
    // deliver short ~2048-sample chunks) into one long analysis window for the FFT.
    private val analysisBuffer = FloatArray(ANALYSIS_WINDOW_SIZE)
    private var analysisBufferFilled = 0

    // --- Long-term key stability state (live streaming path) ---
    private val longTermChroma = FloatArray(12)
    private var stableKeyName: String? = null
    private var candidateKeyName: String? = null
    private var candidateKeyElapsedMs: Float = 0f

    // --- Measure-based chord sustain state (live streaming path) ---
    // name -> (cumulative confidence^2 weight, vote count). Null key represents "no clear
    // chord". Weighting by confidence^2 (rather than raw confidence) so a handful of clean,
    // complete-chord matches outvote many noisy/partial-match frames instead of just
    // averaging with them -- this is what stops the displayed chord from flickering between
    // several plausible candidates within a single measure.
    private var measureVotes = HashMap<String?, Pair<Float, Int>>()
    private var measureElapsedMs: Float = 0f
    private var currentMeasureChord: ChordInfo? = null
    private var currentMeasureConfidence: Float = 0f
    private var bpm: Int = 120
    private var beatsPerMeasure: Int = 4
    private val measureDurationMs: Float
        get() = (60000f / bpm) * beatsPerMeasure

    // --- Adaptive noise floor state (live streaming path) ---
    // adaptiveNoiseFloor stays null until NOISE_CALIBRATION_MS of real audio time has been
    // observed; until then processPcmSamples falls back to its thresholdRms parameter.
    private var noiseCalibrationElapsedMs = 0f
    private var noiseCalibrationRmsSum = 0f
    private var noiseCalibrationFrameCount = 0
    private var adaptiveNoiseFloor: Float? = null

    /**
     * Lets the caller inform the detector of the song's actual tempo/time signature so the
     * "hold chord for a measure" window matches the real bar length. Defaults to 120bpm 4/4
     * (2000ms/measure) if never called. Only affects the live streaming path.
     */
    fun setTempo(bpm: Int, beatsPerMeasure: Int = 4) {
        this.bpm = bpm.coerceIn(20, 300)
        this.beatsPerMeasure = beatsPerMeasure.coerceIn(1, 12)
    }

    fun resetState() {
        lastChromaHistory.clear()
        analysisBuffer.fill(0f)
        analysisBufferFilled = 0

        longTermChroma.fill(0f)
        stableKeyName = null
        candidateKeyName = null
        candidateKeyElapsedMs = 0f

        measureVotes = HashMap()
        measureElapsedMs = 0f
        currentMeasureChord = null
        currentMeasureConfidence = 0f

        // Re-calibrate the noise floor for every new session (mic restarted, mode switched,
        // etc.) rather than carrying over a stale estimate from a previous room/recording.
        noiseCalibrationElapsedMs = 0f
        noiseCalibrationRmsSum = 0f
        noiseCalibrationFrameCount = 0
        adaptiveNoiseFloor = null
    }

    /**
     * Process short PCM samples and detect chord, string energies, and chroma vector.
     */
    fun processPcmSamples(
        pcmShorts: ShortArray,
        thresholdRms: Float = 0.005f
    ): DetectionResult {
        val floats = FloatArray(pcmShorts.size) { pcmShorts[it] / 32768.0f }
        return processPcmSamples(floats, thresholdRms)
    }

    /**
     * Process float PCM samples (-1.0 to 1.0). This is the live/streaming path (mic, real-time
     * file/YouTube playback preview). For uploaded songs, prefer SongAnalyzer's offline,
     * whole-song analysis for a stable key and clean per-measure chords with zero jitter.
     */
    fun processPcmSamples(
        samples: FloatArray,
        thresholdRms: Float = 0.005f
    ): DetectionResult {
        val rms = calculateRms(samples)
        val waveformPreview = extractWaveformPreview(samples, 64)

        // Advance the measure clock using real audio time (not wall-clock), so the "hold for
        // a bar" behavior stays correct regardless of how fast/slow the caller feeds chunks.
        val frameDurationMs = (samples.size.toFloat() / sampleRate) * 1000f
        measureElapsedMs += frameDurationMs

        // --- Adaptive noise floor calibration ---
        // For the first NOISE_CALIBRATION_MS of a session, every incoming frame is assumed to
        // be ambient noise (room hiss, mic self-noise, etc.) rather than a played/sung note,
        // and its RMS is folded into a running average. Once calibration completes, the
        // voice/instrument-activity gate becomes a multiple of that measured floor (clamped
        // to a sane range) instead of the single fixed thresholdRms -- this keeps sensitive
        // mics from treating constant background hiss as "notes", and keeps noisy-room
        // recordings from gating out real playing. Before calibration finishes, thresholdRms
        // is used as-is.
        if (adaptiveNoiseFloor == null) {
            noiseCalibrationRmsSum += rms
            noiseCalibrationFrameCount++
            noiseCalibrationElapsedMs += frameDurationMs
            if (noiseCalibrationElapsedMs >= NOISE_CALIBRATION_MS) {
                adaptiveNoiseFloor = noiseCalibrationRmsSum / noiseCalibrationFrameCount.coerceAtLeast(1)
            }
        }

        val effectiveThresholdRms = adaptiveNoiseFloor?.let { floor ->
            (floor * NOISE_FLOOR_MULTIPLIER).coerceIn(MIN_ADAPTIVE_THRESHOLD, MAX_ADAPTIVE_THRESHOLD)
        } ?: thresholdRms

        // Feed the rolling analysis buffer regardless of RMS gate, so it stays warm.
        pushToAnalysisBuffer(samples)

        if (rms < effectiveThresholdRms) {
            finalizeMeasureIfNeeded()
            return DetectionResult(
                chord = null,
                confidence = 0f,
                estimatedKey = stableKeyName,
                chromaVector = FloatArray(12),
                stringEnergies = FloatArray(6),
                waveform = waveformPreview,
                status = DetectionStatus.LISTENING
            )
        }

        // Wait until we have a full real analysis window before trusting the FFT --
        // otherwise early frames are mostly zero-padding and produce noisy guesses.
        if (analysisBufferFilled < ANALYSIS_WINDOW_SIZE) {
            finalizeMeasureIfNeeded()
            return DetectionResult(
                chord = null,
                confidence = 0f,
                estimatedKey = stableKeyName,
                chromaVector = FloatArray(12),
                stringEnergies = FloatArray(6),
                waveform = waveformPreview,
                status = DetectionStatus.PROCESSING
            )
        }

        // 1-3. Low-pass filter + windowed FFT + chroma extraction. Shared with offline
        //      analysis (via analyzeChroma) so live and offline paths can't drift apart.
        val (magnitudes, chromaVector) = computeMagnitudesAndChroma(analysisBuffer)
        val binWidthHz = sampleRate.toFloat() / ANALYSIS_WINDOW_SIZE

        // 3b. Identify the loudest pitch class in the bass register. A chord's root is far
        //     better identified from its bass note than from whichever pitch class happens
        //     to be loudest overall (which is often a doubled string, an overtone, or the
        //     melody note ringing on top) -- this directly targets the "detection latches
        //     onto a note that actually belongs to a different chord" problem.
        val bassPitchClass = estimateBassPitchClass(magnitudes, binWidthHz)

        // 4. Smooth Chroma Vector across a few recent frames (short-term, for live chroma
        //    bar / string-energy visuals -- intentionally NOT the same signal used for key
        //    estimation below).
        val smoothedChroma = smoothChroma(chromaVector)

        // 5. Calculate Guitar String Energies
        val stringEnergies = FloatArray(6)
        for (s in 0 until 6) {
            val stringFreq = GUITAR_STRING_FREQS[s]
            var totalEnergy = 0f
            // Sum harmonics 1x, 2x, 3x
            for (harmonic in 1..3) {
                val hFreq = stringFreq * harmonic
                val bin = (hFreq / binWidthHz).roundToInt()
                if (bin in 1 until magnitudes.size) {
                    totalEnergy += magnitudes[bin]
                }
            }
            stringEnergies[s] = totalEnergy
        }
        val maxStringEnergy = stringEnergies.maxOrNull() ?: 0f
        if (maxStringEnergy > 0.05f) {
            for (i in 0 until 6) {
                stringEnergies[i] = (stringEnergies[i] / maxStringEnergy).coerceIn(0f, 1f)
            }
        } else {
            stringEnergies.fill(0f)
        }

        // 6. Update the long-term key estimate. This chroma accumulator decays very slowly
        //    (LONG_TERM_CHROMA_DECAY ~0.999) so it represents the song's cumulative tonal
        //    center rather than the current chord -- a single chord's chroma barely moves it.
        for (i in 0 until 12) {
            longTermChroma[i] = longTermChroma[i] * LONG_TERM_CHROMA_DECAY +
                chromaVector[i] * (1f - LONG_TERM_CHROMA_DECAY)
        }
        val candidateKey = estimateKeyFromChroma(normalizeVector(longTermChroma))
        updateStableKey(candidateKey, frameDurationMs)

        // 7. Template Match against Chord Database with Unbiased Scoring (per-frame best
        //    guess -- this feeds the per-measure vote below rather than being shown directly).
        val (bestChord, confidence) = matchChord(smoothedChroma, bassPitchClass = bassPitchClass)

        // 8. Cast this frame's vote into the current measure's tally, weighted by
        //    confidence^2 so a handful of clean, complete-chord matches decisively outvote
        //    many noisy/ambiguous frames instead of being diluted by averaging with them.
        if (confidence > MEASURE_VOTE_MIN_CONFIDENCE) {
            val voteKey = bestChord?.name
            val prev = measureVotes[voteKey] ?: (0f to 0)
            val weighted = confidence * confidence
            measureVotes[voteKey] = (prev.first + weighted) to (prev.second + 1)
        }
        finalizeMeasureIfNeeded()

        return DetectionResult(
            chord = currentMeasureChord,
            confidence = currentMeasureConfidence,
            estimatedKey = stableKeyName,
            chromaVector = smoothedChroma,
            stringEnergies = stringEnergies,
            waveform = waveformPreview,
            status = if (currentMeasureChord != null) DetectionStatus.DETECTED else DetectionStatus.PROCESSING
        )
    }

    // Reusable scratch buffers for zero-allocation window processing
    private val scratchFiltered = FloatArray(ANALYSIS_WINDOW_SIZE)
    private val scratchReal = FloatArray(ANALYSIS_WINDOW_SIZE)
    private val scratchImag = FloatArray(ANALYSIS_WINDOW_SIZE)

    /**
     * Computes a normalized 12-bin chroma vector for an arbitrary window of raw mono float
     * PCM samples (window size should be a power of two -- SongAnalyzer always uses
     * ANALYSIS_WINDOW_SIZE). Public so SongAnalyzer's offline, whole-song analysis uses
     * exactly the same signal processing as the live streaming path.
     */
    fun analyzeChroma(samples: FloatArray, offset: Int = 0, length: Int = samples.size): FloatArray {
        return analyzeChromaWithBass(samples, offset, length).first
    }

    /**
     * Same analysis as [analyzeChroma], but also returns the loudest bass-register pitch
     * class (or -1 if the window is silent/too quiet to trust). SongAnalyzer's offline
     * per-measure matching uses this so chord root identification -- not just overall
     * template similarity -- is grounded in the actual bass note, exactly like the live
     * streaming path does inside processPcmSamples().
     */
    fun analyzeChromaWithBass(samples: FloatArray, offset: Int = 0, length: Int = samples.size): Pair<FloatArray, Int> {
        if (length <= 0 || offset < 0 || offset + length > samples.size) {
            return FloatArray(12) to -1
        }

        // Cheap RMS check: skip near-silent windows before running filter + 16384-point FFT
        var sumSq = 0f
        val stride = 8
        var i = offset
        val end = offset + length
        var count = 0
        while (i < end) {
            val s = samples[i]
            sumSq += s * s
            i += stride
            count++
        }
        val approxRms = sqrt(sumSq / count.coerceAtLeast(1))
        if (approxRms < 0.003f) {
            return FloatArray(12) to -1
        }

        val (magnitudes, chroma) = computeMagnitudesAndChroma(samples, offset, length)
        val binWidthHz = sampleRate.toFloat() / length
        val bassPitchClass = estimateBassPitchClass(magnitudes, binWidthHz)
        return chroma to bassPitchClass
    }

    /**
     * Finds the loudest pitch class within the guitar's bass/root register only
     * (roughly E2-B2, ~70-260Hz). A chord's identity is defined far more reliably by its
     * bass note than by whatever pitch class happens to ring loudest across the whole
     * spectrum -- the overall-loudest bin is often a doubled string, a harmonic overtone,
     * or a melody note ringing on top, any of which can point evaluateChordScore toward a
     * chord that isn't actually the one being played. Returns -1 if the bass register has
     * no meaningful energy (can't be trusted as a root hint).
     */
    private fun estimateBassPitchClass(magnitudes: FloatArray, binWidthHz: Float): Int {
        if (binWidthHz <= 0f) return -1
        val lowBin = (BASS_SEARCH_MIN_HZ / binWidthHz).toInt().coerceAtLeast(1)
        val highBin = (BASS_SEARCH_MAX_HZ / binWidthHz).toInt().coerceAtMost(magnitudes.size - 1)
        if (lowBin > highBin) return -1

        var bestBin = -1
        var bestMag = 0f
        for (bin in lowBin..highBin) {
            if (magnitudes[bin] > bestMag) {
                bestMag = magnitudes[bin]
                bestBin = bin
            }
        }
        if (bestBin < 0 || bestMag < 0.001f) return -1

        val freq = bestBin * binWidthHz
        val midiPitch = 69.0f + 12.0f * log2(freq / 440.0f)
        return (midiPitch.roundToInt() % 12 + 12) % 12
    }

    /**
     * Finds the best-matching chord for a chroma vector against a candidate list (defaults
     * to the full chord database). Returns the chord and a 0..1 confidence score. Shared by
     * the live streaming path and SongAnalyzer's offline per-measure matching -- SongAnalyzer
     * passes a restricted candidate list (diatonic-only) for "Simple" mode.
     *
     * @param bassPitchClass the loudest pitch class in the bass register (from
     *   [estimateBassPitchClass] / [analyzeChromaWithBass]), or -1 if unknown. When a
     *   candidate chord's root matches this, its score gets a bonus -- this is what lets
     *   the detector correctly favor (say) an Am chord over a C chord even though both
     *   share two of the same notes, as long as the bass is clearly on A.
     */
    fun matchChord(
        chroma: FloatArray,
        candidates: List<ChordInfo> = chordDatabase,
        bassPitchClass: Int = -1
    ): Pair<ChordInfo?, Float> {
        var bestChord: ChordInfo? = null
        var bestScore = 0f

        for (chord in candidates) {
            val score = evaluateChordScore(chroma, chord, bassPitchClass)
            if (score > bestScore) {
                bestScore = score
                bestChord = chord
            }
        }

        val confidence = (bestScore * 1.15f).coerceIn(0f, 1f)
        return bestChord to confidence
    }

    /**
     * Krumhansl-Schmuckler Key Finder:
     * Calculates Pearson correlation between chroma vector and 24 major/minor key profiles.
     * Public so SongAnalyzer can call it directly on a whole-song chroma accumulation.
     */
    fun estimateKeyFromChroma(chroma: FloatArray): String {
        val krumhanslMajor = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
        val krumhanslMinor = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 2.69f, 3.34f, 3.17f, 3.28f)

        var bestKeyName = "C Major"
        var bestCorrelation = -2f

        for (rootShift in 0 until 12) {
            val rootName = NOTE_NAMES[rootShift]

            // Test Major profile
            val rotatedMajor = FloatArray(12) { krumhanslMajor[(it - rootShift + 12) % 12] }
            val corrMaj = pearsonCorrelation(chroma, rotatedMajor)
            if (corrMaj > bestCorrelation) {
                bestCorrelation = corrMaj
                bestKeyName = "Key of $rootName Major"
            }

            // Test Minor profile
            val rotatedMinor = FloatArray(12) { krumhanslMinor[(it - rootShift + 12) % 12] }
            val corrMin = pearsonCorrelation(chroma, rotatedMinor)
            if (corrMin > bestCorrelation) {
                bestCorrelation = corrMin
                bestKeyName = "Key of ${rootName}m (Minor)"
            }
        }

        return bestKeyName
    }

    /**
     * If a full measure's worth of real audio time has elapsed, commit whichever chord
     * accumulated the most confidence^2-weighted votes as the chord to display, and hold it
     * until the next measure boundary. This is what makes the on-screen chord change on
     * bar boundaries instead of flickering every FFT frame.
     */
    private fun finalizeMeasureIfNeeded() {
        if (measureElapsedMs < measureDurationMs) return

        val winnerEntry = measureVotes.maxByOrNull { it.value.first }
        val winnerName = winnerEntry?.key
        currentMeasureChord = if (winnerName != null) {
            chordDatabase.find { it.name == winnerName }
        } else {
            null
        }
        // Votes were accumulated as confidence^2 (see processPcmSamples step 8); take the
        // square root back to restore a genuine 0..1 confidence value for display.
        currentMeasureConfidence = winnerEntry?.let {
            if (it.value.second > 0) sqrt((it.value.first / it.value.second).toDouble()).toFloat() else 0f
        } ?: 0f

        measureVotes = HashMap()
        measureElapsedMs = 0f
    }

    /**
     * Applies hysteresis to the key estimate: a new candidate key must remain different from
     * the current stable key for KEY_STABILITY_HOLD_MS of real audio time before it's
     * accepted. This keeps the displayed key static through normal chord movement, while
     * still allowing a real modulation (a section that goes up/down a key) to register after
     * it's been sustained for a while.
     */
    private fun updateStableKey(candidateKey: String, frameDurationMs: Float) {
        if (stableKeyName == null || candidateKey == stableKeyName) {
            stableKeyName = candidateKey
            candidateKeyName = null
            candidateKeyElapsedMs = 0f
            return
        }

        if (candidateKey == candidateKeyName) {
            candidateKeyElapsedMs += frameDurationMs
            if (candidateKeyElapsedMs >= KEY_STABILITY_HOLD_MS) {
                stableKeyName = candidateKey
                candidateKeyName = null
                candidateKeyElapsedMs = 0f
            }
        } else {
            candidateKeyName = candidateKey
            candidateKeyElapsedMs = frameDurationMs
        }
    }

    /**
     * Shifts new samples into the rolling analysis buffer (FIFO), so the FFT always
     * operates on the most recent ANALYSIS_WINDOW_SIZE real samples rather than a
     * short zero-padded chunk.
     */
    private fun pushToAnalysisBuffer(newSamples: FloatArray) {
        val incoming = if (newSamples.size >= analysisBuffer.size) {
            newSamples.copyOfRange(newSamples.size - analysisBuffer.size, newSamples.size)
        } else {
            newSamples
        }

        val shiftAmount = incoming.size
        if (shiftAmount >= analysisBuffer.size) {
            System.arraycopy(incoming, 0, analysisBuffer, 0, analysisBuffer.size)
        } else {
            System.arraycopy(analysisBuffer, shiftAmount, analysisBuffer, 0, analysisBuffer.size - shiftAmount)
            System.arraycopy(incoming, 0, analysisBuffer, analysisBuffer.size - shiftAmount, shiftAmount)
        }
        analysisBufferFilled = (analysisBufferFilled + shiftAmount).coerceAtMost(analysisBuffer.size)
    }

    /**
     * Simple one-pole IIR low-pass filter. Used to attenuate percussion transients,
     * cymbals, and vocal sibilance above the guitar's useful fundamental+harmonic
     * range before chroma extraction, so full song mixes don't pollute the chord match.
     */
    private fun lowPassFilterInPlace(
        input: FloatArray,
        offset: Int,
        length: Int,
        output: FloatArray,
        cutoffHz: Float
    ) {
        val rc = 1.0f / (2f * PI.toFloat() * cutoffHz)
        val dt = 1.0f / sampleRate
        val alpha = dt / (rc + dt)
        output[0] = input[offset]
        for (i in 1 until length) {
            output[i] = output[i - 1] + alpha * (input[offset + i] - output[i - 1])
        }
    }

    /**
     * Shared core of chroma extraction: low-pass filter -> Hanning-windowed FFT -> 12-bin
     * chroma vector. Returns both the raw FFT magnitude spectrum (needed by the live path
     * for guitar string-energy calculation and bass-note detection) and the normalized
     * chroma vector. Used by both processPcmSamples (live) and analyzeChromaWithBass
     * (offline), so the two paths can never silently diverge in behavior.
     */
    private fun computeMagnitudesAndChroma(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size
    ): Pair<FloatArray, FloatArray> {
        val n = length
        val filtered = if (n == ANALYSIS_WINDOW_SIZE) scratchFiltered else FloatArray(n)
        lowPassFilterInPlace(samples, offset, n, filtered, LOWPASS_CUTOFF_HZ)

        val real = if (n == ANALYSIS_WINDOW_SIZE) scratchReal else FloatArray(n)
        val imag = if (n == ANALYSIS_WINDOW_SIZE) scratchImag else FloatArray(n)
        imag.fill(0f)

        for (i in 0 until n) {
            val hann = 0.5f * (1.0f - cos(2.0 * PI * i / (n - 1)).toFloat())
            real[i] = filtered[i] * hann
        }

        computeFftInPlace(real, imag)

        val half = n / 2
        val magnitudes = FloatArray(half)
        for (i in 0 until half) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        val binWidthHz = sampleRate.toFloat() / n
        val rawChroma = FloatArray(12)
        for (k in 1 until magnitudes.size) {
            val freq = k * binWidthHz
            if (freq in 60.0f..2200.0f) {
                val mag = magnitudes[k]
                if (mag > 0.001f) {
                    val midiPitch = 69.0f + 12.0f * log2(freq / 440.0f)
                    val chromaIndex = (midiPitch.roundToInt() % 12 + 12) % 12
                    rawChroma[chromaIndex] += mag * mag
                }
            }
        }

        return magnitudes to normalizeVector(rawChroma)
    }

    private fun calculateRms(samples: FloatArray): Float {
        var sum = 0f
        for (s in samples) {
            sum += s * s
        }
        return sqrt(sum / samples.size)
    }

    private fun extractWaveformPreview(samples: FloatArray, targetSize: Int): FloatArray {
        val preview = FloatArray(targetSize)
        val step = samples.size.toFloat() / targetSize
        for (i in 0 until targetSize) {
            val idx = (i * step).toInt().coerceIn(0, samples.lastIndex)
            preview[i] = samples[idx]
        }
        return preview
    }

    private fun smoothChroma(chroma: FloatArray): FloatArray {
        lastChromaHistory.add(chroma.clone())
        if (lastChromaHistory.size > maxHistoryFrames) {
            lastChromaHistory.removeAt(0)
        }

        val result = FloatArray(12)
        for (c in 0 until 12) {
            var sum = 0f
            for (frame in lastChromaHistory) {
                sum += frame[c]
            }
            result[c] = sum / lastChromaHistory.size
        }
        return normalizeVector(result)
    }

    /**
     * Unbiased Chord Evaluator:
     * Evaluates chord match without favoring any specific root or template position.
     *
     * Two accuracy improvements over a plain weighted-energy match:
     *  1. "Completeness" -- a chord only scores well if most of ITS OWN notes are actually
     *     audible (>= NOTE_PRESENCE_THRESHOLD), not just whichever single note happens to be
     *     loudest. Without this, one strong pitch class (a doubled string, an overtone, mic
     *     bleed) can make an unrelated chord that merely contains that note look like a
     *     decent match, which is what causes detection to jump between several chords that
     *     all happen to share one note.
     *  2. Bass-root agreement -- if the chord's root matches the actual bass-register pitch
     *     class (passed in from estimateBassPitchClass), its score gets a bonus. This lets
     *     e.g. Am correctly outscore C even though they share two notes, whenever the bass
     *     is clearly on A rather than C.
     */
    private fun evaluateChordScore(chroma: FloatArray, chord: ChordInfo, bassPitchClass: Int = -1): Float {
        val template = FloatArray(12)
        for (note in chord.notes) {
            val idx = NOTE_NAMES.indexOf(note)
            if (idx >= 0) {
                template[idx] = 1.0f
            }
        }

        val rootIdx = NOTE_NAMES.indexOf(chord.rootNote)
        if (rootIdx >= 0) {
            // Emphasize root note slightly for all chords equally
            template[rootIdx] = 1.25f
        }

        val normTemplate = normalizeVector(template)

        var matchedEnergy = 0f
        var unmatchedNoiseEnergy = 0f
        var templateSum = 0f
        var presentNoteCount = 0
        val templateNoteCount = chord.notes.distinct().size

        for (i in 0 until 12) {
            templateSum += normTemplate[i]
            if (normTemplate[i] > 0f) {
                matchedEnergy += chroma[i] * normTemplate[i]
                if (chroma[i] >= NOTE_PRESENCE_THRESHOLD) {
                    presentNoteCount++
                }
            } else {
                if (chroma[i] > 0.2f) {
                    unmatchedNoiseEnergy += chroma[i] * 0.35f
                }
            }
        }

        if (templateSum == 0f || templateNoteCount == 0) return 0f
        val matchRatio = matchedEnergy / templateSum

        // A chord shouldn't win just because ONE loud note happens to be in its template --
        // most of its notes need to actually be audible. The floor keeps a partial-but-real
        // match from being zeroed out entirely.
        val completeness = presentNoteCount.toFloat() / templateNoteCount
        val completenessFactor = COMPLETENESS_SCORE_FLOOR + (1f - COMPLETENESS_SCORE_FLOOR) * completeness

        var score = ((matchRatio * completenessFactor) - unmatchedNoiseEnergy).coerceIn(0f, 1f)

        if (bassPitchClass >= 0 && rootIdx == bassPitchClass) {
            score = (score * BASS_ROOT_MATCH_BONUS).coerceAtMost(1f)
        }

        return score
    }

    private fun pearsonCorrelation(x: FloatArray, y: FloatArray): Float {
        var sumX = 0f
        var sumY = 0f
        val n = x.size
        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var num = 0f
        var denX = 0f
        var denY = 0f
        for (i in 0 until n) {
            val diffX = x[i] - meanX
            val diffY = y[i] - meanY
            num += diffX * diffY
            denX += diffX * diffX
            denY += diffY * diffY
        }

        val den = sqrt(denX * denY)
        return if (den == 0f) 0f else num / den
    }

    private fun normalizeVector(v: FloatArray): FloatArray {
        var maxVal = 0f
        for (x in v) {
            if (x > maxVal) maxVal = x
        }
        if (maxVal == 0f) return FloatArray(v.size)
        return FloatArray(v.size) { v[it] / maxVal }
    }

    private fun getNextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) {
            p = p shl 1
        }
        return p
    }

    /**
     * Radix-2 Cooley-Tukey in-place FFT implementation in Kotlin (zero allocations)
     */
    private fun computeFftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // FFT computation
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (m in 0 until halfLen) {
                    val pos = i + m + halfLen
                    val uR = real[i + m]
                    val uI = imag[i + m]
                    val tR = real[pos] * wR - imag[pos] * wI
                    val tI = real[pos] * wI + imag[pos] * wR

                    real[i + m] = uR + tR
                    imag[i + m] = uI + tI
                    real[pos] = uR - tR
                    imag[pos] = uI - tI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun buildChordDatabase(): List<ChordInfo> {
        val list = mutableListOf<ChordInfo>()

        // Helper maps for intervals
        val roots = NOTE_NAMES

        for (rIdx in roots.indices) {
            val root = roots[rIdx]

            fun noteAt(semitones: Int): String {
                return NOTE_NAMES[(rIdx + semitones) % 12]
            }

            // 1. Major Chord (Root, +4, +7)
            val cMaj = noteAt(0)
            val eMaj = noteAt(4)
            val gMaj = noteAt(7)
            list.add(
                ChordInfo(
                    name = "$root",
                    rootNote = root,
                    chordType = "Major",
                    notes = listOf(cMaj, eMaj, gMaj),
                    fretDiagram = getFretDiagramForChord(root, "Major"),
                    description = "Bright, harmonious major triad ($cMaj - $eMaj - $gMaj)"
                )
            )

            // 2. Minor Chord (Root, +3, +7)
            val eMin = noteAt(3)
            list.add(
                ChordInfo(
                    name = "${root}m",
                    rootNote = root,
                    chordType = "Minor",
                    notes = listOf(cMaj, eMin, gMaj),
                    fretDiagram = getFretDiagramForChord(root, "Minor"),
                    description = "Warm, reflective minor triad ($cMaj - $eMin - $gMaj)"
                )
            )

            // 3. Dominant 7th (Root, +4, +7, +10)
            val b7 = noteAt(10)
            list.add(
                ChordInfo(
                    name = "${root}7",
                    rootNote = root,
                    chordType = "Dominant 7th",
                    notes = listOf(cMaj, eMaj, gMaj, b7),
                    fretDiagram = getFretDiagramForChord(root, "7"),
                    description = "Bluesy dominant 7th ($cMaj - $eMaj - $gMaj - $b7)"
                )
            )

            // 4. Minor 7th (Root, +3, +7, +10)
            list.add(
                ChordInfo(
                    name = "${root}m7",
                    rootNote = root,
                    chordType = "Minor 7th",
                    notes = listOf(cMaj, eMin, gMaj, b7),
                    fretDiagram = getFretDiagramForChord(root, "m7"),
                    description = "Smooth jazz minor 7th ($cMaj - $eMin - $gMaj - $b7)"
                )
            )

            // 5. Major 7th (Root, +4, +7, +11)
            val maj7Note = noteAt(11)
            list.add(
                ChordInfo(
                    name = "${root}maj7",
                    rootNote = root,
                    chordType = "Major 7th",
                    notes = listOf(cMaj, eMaj, gMaj, maj7Note),
                    fretDiagram = getFretDiagramForChord(root, "maj7"),
                    description = "Lush, open major 7th ($cMaj - $eMaj - $gMaj - $maj7Note)"
                )
            )

            // 6. Power Chord / 5th (Root, +7)
            list.add(
                ChordInfo(
                    name = "${root}5",
                    rootNote = root,
                    chordType = "5th (Power)",
                    notes = listOf(cMaj, gMaj),
                    fretDiagram = getFretDiagramForChord(root, "5"),
                    description = "Driving rock power chord ($cMaj - $gMaj)"
                )
            )

            // 7. Sus4 (Root, +5, +7)
            val sus4Note = noteAt(5)
            list.add(
                ChordInfo(
                    name = "${root}sus4",
                    rootNote = root,
                    chordType = "Sus4",
                    notes = listOf(cMaj, sus4Note, gMaj),
                    fretDiagram = getFretDiagramForChord(root, "sus4"),
                    description = "Suspended 4th chord ($cMaj - $sus4Note - $gMaj)"
                )
            )

            // 8. Diminished (Root, +3, +6) -- needed so "Simple" mode can represent the
            //    vii° diatonic chord in major keys (and ii° in natural minor keys). No
            //    curated fret diagram data exists for these yet, so they fall back to the
            //    generic default shape in getFretDiagramForChord below.
            val dimNote = noteAt(6)
            list.add(
                ChordInfo(
                    name = "${root}dim",
                    rootNote = root,
                    chordType = "Diminished",
                    notes = listOf(cMaj, eMin, dimNote),
                    fretDiagram = getFretDiagramForChord(root, "dim"),
                    description = "Tense, unresolved diminished triad ($cMaj - $eMin - $dimNote)"
                )
            )
        }

        return list
    }

    private fun getFretDiagramForChord(root: String, type: String): FretDiagram {
        val frets = when ("$root $type") {
            // C
            "C Major" -> intArrayOf(-1, 3, 2, 0, 1, 0)
            "C Minor" -> intArrayOf(-1, 3, 5, 5, 4, 3)
            "C 7" -> intArrayOf(-1, 3, 2, 3, 1, 0)
            "C m7" -> intArrayOf(-1, 3, 1, 3, 1, 3)
            "C maj7" -> intArrayOf(-1, 3, 2, 0, 0, 0)
            "C 5" -> intArrayOf(-1, 3, 5, 5, -1, -1)

            // D
            "D Major" -> intArrayOf(-1, -1, 0, 2, 3, 2)
            "D Minor" -> intArrayOf(-1, -1, 0, 2, 3, 1)
            "D 7" -> intArrayOf(-1, -1, 0, 2, 1, 2)
            "D m7" -> intArrayOf(-1, -1, 0, 2, 1, 1)
            "D maj7" -> intArrayOf(-1, -1, 0, 2, 2, 2)
            "D 5" -> intArrayOf(-1, -1, 0, 2, 3, -1)

            // E
            "E Major" -> intArrayOf(0, 2, 2, 1, 0, 0)
            "E Minor" -> intArrayOf(0, 2, 2, 0, 0, 0)
            "E 7" -> intArrayOf(0, 2, 0, 1, 0, 0)
            "E m7" -> intArrayOf(0, 2, 0, 0, 0, 0)
            "E maj7" -> intArrayOf(0, 2, 1, 1, 0, 0)
            "E 5" -> intArrayOf(0, 2, 2, -1, -1, -1)

            // F
            "F Major" -> intArrayOf(1, 3, 3, 2, 1, 1)
            "F Minor" -> intArrayOf(1, 3, 3, 1, 1, 1)
            "F 7" -> intArrayOf(1, 3, 1, 2, 1, 1)
            "F m7" -> intArrayOf(1, 3, 1, 1, 1, 1)
            "F maj7" -> intArrayOf(-1, 3, 3, 2, 1, 0)
            "F 5" -> intArrayOf(1, 3, 3, -1, -1, -1)

            // G
            "G Major" -> intArrayOf(3, 2, 0, 0, 0, 3)
            "G Minor" -> intArrayOf(3, 5, 5, 3, 3, 3)
            "G 7" -> intArrayOf(3, 2, 0, 0, 0, 1)
            "G m7" -> intArrayOf(3, 5, 3, 3, 3, 3)
            "G maj7" -> intArrayOf(3, 2, 0, 0, 0, 2)
            "G 5" -> intArrayOf(3, 5, 5, -1, -1, -1)

            // A
            "A Major" -> intArrayOf(-1, 0, 2, 2, 2, 0)
            "A Minor" -> intArrayOf(-1, 0, 2, 2, 1, 0)
            "A 7" -> intArrayOf(-1, 0, 2, 0, 2, 0)
            "A m7" -> intArrayOf(-1, 0, 2, 0, 1, 0)
            "A maj7" -> intArrayOf(-1, 0, 2, 1, 2, 0)
            "A 5" -> intArrayOf(-1, 0, 2, 2, -1, -1)

            // B
            "B Major" -> intArrayOf(-1, 2, 4, 4, 4, 2)
            "B Minor" -> intArrayOf(-1, 2, 4, 4, 3, 2)
            "B 7" -> intArrayOf(-1, 2, 1, 2, 0, 2)
            "B m7" -> intArrayOf(-1, 2, 0, 2, 0, 2)
            "B maj7" -> intArrayOf(-1, 2, 4, 3, 4, 2)
            "B 5" -> intArrayOf(-1, 2, 4, 4, -1, -1)

            else -> intArrayOf(-1, 0, 2, 2, 1, 0)
        }

        return FretDiagram(frets)
    }
}