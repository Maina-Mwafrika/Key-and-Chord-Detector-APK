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
    }

    private val chordDatabase: List<ChordInfo> = buildChordDatabase()
    private val lastChromaHistory = mutableListOf<FloatArray>()
    private val maxHistoryFrames = 4

    // Hysteresis tracking state for smooth chord transitions
    private var stableChord: ChordInfo? = null
    private var candidateChord: ChordInfo? = null
    private var candidateFrameCount: Int = 0
    private val requiredHoldFrames: Int = 3 // ~120ms-150ms transition delay window

    fun resetState() {
        lastChromaHistory.clear()
        stableChord = null
        candidateChord = null
        candidateFrameCount = 0
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
     * Process float PCM samples (-1.0 to 1.0)
     */
    fun processPcmSamples(
        samples: FloatArray,
        thresholdRms: Float = 0.005f
    ): DetectionResult {
        val rms = calculateRms(samples)
        val waveformPreview = extractWaveformPreview(samples, 64)

        if (rms < thresholdRms) {
            return DetectionResult(
                chord = null,
                confidence = 0f,
                chromaVector = FloatArray(12),
                stringEnergies = FloatArray(6),
                waveform = waveformPreview,
                status = DetectionStatus.LISTENING
            )
        }

        // 1. Compute FFT Magnitudes
        val n = getNextPowerOfTwo(samples.size.coerceAtMost(2048))
        val fftBuffer = FloatArray(n)
        for (i in 0 until min(samples.size, n)) {
            // Hanning Window
            val window = 0.5f * (1.0f - cos(2.0 * PI * i / (n - 1)).toFloat())
            fftBuffer[i] = samples[i] * window
        }

        val magnitudes = computeFftMagnitudes(fftBuffer)

        // 2. Build Chroma Vector
        val rawChroma = FloatArray(12)
        val binWidthHz = sampleRate.toFloat() / n

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

        // Normalize raw chroma
        val chromaVector = normalizeVector(rawChroma)

        // 3. Smooth Chroma Vector across recent frames
        val smoothedChroma = smoothChroma(chromaVector)

        // 4. Calculate Guitar String Energies
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
        val maxStringEnergy = stringEnergies.maxOrNull() ?: 1.0f
        if (maxStringEnergy > 0f) {
            for (i in 0 until 6) {
                stringEnergies[i] = (stringEnergies[i] / maxStringEnergy).coerceIn(0f, 1f)
            }
        }

        // 5. Estimate Musical Key using Krumhansl-Schmuckler Key Profiling Algorithm
        val estimatedKey = estimateKeyFromChroma(smoothedChroma)

        // 6. Template Match against Chord Database with Unbiased Scoring
        var bestChord: ChordInfo? = null
        var bestScore = 0f

        for (chord in chordDatabase) {
            val score = evaluateChordScore(smoothedChroma, chord)
            if (score > bestScore) {
                bestScore = score
                bestChord = chord
            }
        }

        // Dynamic Confidence mapping
        val confidence = (bestScore * 1.15f).coerceIn(0f, 1f)

        val rawDetectedChord = if (confidence > 0.35f) bestChord else null

        // Apply tracking transition delay & debouncing hysteresis
        if (rawDetectedChord?.name == stableChord?.name) {
            candidateChord = null
            candidateFrameCount = 0
        } else {
            if (rawDetectedChord?.name == candidateChord?.name) {
                candidateFrameCount++
                if (candidateFrameCount >= requiredHoldFrames) {
                    stableChord = candidateChord
                    candidateChord = null
                    candidateFrameCount = 0
                }
            } else {
                candidateChord = rawDetectedChord
                candidateFrameCount = 1
            }
        }

        val displayChord = stableChord

        return DetectionResult(
            chord = displayChord,
            confidence = confidence,
            estimatedKey = if (confidence > 0.25f) estimatedKey else null,
            chromaVector = smoothedChroma,
            stringEnergies = stringEnergies,
            waveform = waveformPreview,
            status = if (displayChord != null) DetectionStatus.DETECTED else DetectionStatus.PROCESSING
        )
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
     */
    private fun evaluateChordScore(chroma: FloatArray, chord: ChordInfo): Float {
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

        for (i in 0 until 12) {
            templateSum += normTemplate[i]
            if (normTemplate[i] > 0f) {
                matchedEnergy += chroma[i] * normTemplate[i]
            } else {
                if (chroma[i] > 0.2f) {
                    unmatchedNoiseEnergy += chroma[i] * 0.35f
                }
            }
        }

        if (templateSum == 0f) return 0f
        val matchRatio = matchedEnergy / templateSum

        return (matchRatio - unmatchedNoiseEnergy).coerceIn(0f, 1f)
    }

    /**
     * Krumhansl-Schmuckler Key Finder:
     * Calculates Pearson correlation between chroma vector and 24 major/minor key profiles.
     */
    private fun estimateKeyFromChroma(chroma: FloatArray): String {
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
     * Radix-2 Cooley-Tukey FFT implementation in Kotlin
     */
    private fun computeFftMagnitudes(input: FloatArray): FloatArray {
        val n = input.size
        val real = input.clone()
        val imag = FloatArray(n)

        // Bit reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
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

        val half = n / 2
        val magnitudes = FloatArray(half)
        for (i in 0 until half) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        return magnitudes
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
