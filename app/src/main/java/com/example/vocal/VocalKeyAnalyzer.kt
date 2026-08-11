package com.example.vocal

import android.util.Log
import com.example.audio.ChordInfo
import com.example.audio.ChromaChordDetector
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class VocalKeyAnalyzer(private val sampleRate: Int = 44100) {

    private val detector = ChromaChordDetector(sampleRate)
    private val TAG = "VocalKeyAnalyzer"

    companion object {
        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 2.69, 3.34, 3.17, 3.28)

        // --- Adaptive noise floor tuning (post-hoc analysis of a full recording) ---
        // A single fixed RMS gate is either too strict for a quiet mic/singer or too loose
        // (picks up breath noise / room hiss) for a hot mic. Instead, estimate a noise floor
        // directly from this recording's own RMS distribution: most singing has real silence
        // or breath gaps between phrases, so a low percentile of all analysis windows' RMS
        // values is a reasonable stand-in for "ambient noise" even without a dedicated silent
        // calibration period at the start. The gate is then set as a multiple of that floor,
        // clamped to a sane range so a recording with no quiet moments at all doesn't end up
        // with a near-zero (everything passes) or absurdly high (nothing passes) threshold.
        private const val NOISE_FLOOR_PERCENTILE = 0.15
        private const val NOISE_FLOOR_MULTIPLIER = 2.5f
        private const val MIN_ADAPTIVE_RMS_THRESHOLD = 0.006f
        private const val MAX_ADAPTIVE_RMS_THRESHOLD = 0.05f

        fun midiToNoteName(midi: Int): String {
            val noteIndex = ((midi % 12) + 12) % 12
            val octave = (midi / 12) - 1
            return "${NOTE_NAMES[noteIndex]}$octave"
        }

        fun extractRootFromKey(keyString: String): Pair<String, Boolean> {
            val clean = keyString.replace("Key of ", "").trim()
            val isMinor = clean.contains("Minor") || clean.contains("m ") || clean.endsWith("m")
            val rootName = clean.split(" ")[0].replace("m", "")
            return rootName to isMinor
        }
    }

    init {
        require(sampleRate in 16000..48000) {
            "Sample rate must be between 16kHz and 48kHz"
        }
    }

    fun analyzeSingingSample(
        pcmBuffers: List<ShortArray>,
        manualTranspositionOffset: Int? = null
    ): VocalAnalysisResult {
        require(pcmBuffers.isNotEmpty()) { "Audio buffers cannot be empty" }

        val allChroma = FloatArray(12)
        val pitchMidiList = mutableListOf<Int>()
        val timeFrameChromaList = mutableListOf<FloatArray>()

        val windowSize = 4096
        val stepSize = 2048
        val window = FloatArray(windowSize)
        var frameCount = 0

        Log.d(TAG, "Processing ${pcmBuffers.size} audio buffers")

        // Flatten all mic chunks into one continuous stream first. Individual
        // AudioRecord reads are ~2048 samples -- smaller than the 4096-sample
        // analysis window -- so windowing per-buffer (the old code) could never
        // fit a single full window and silently produced zero frames on every
        // recording, forcing the hardcoded fallback below. Concatenating lets
        // windows span buffer boundaries so real chroma/pitch data is captured.
        val totalSamples = pcmBuffers.sumOf { it.size }
        val floatBuf = FloatArray(totalSamples)
        var writeOffset = 0
        for (buffer in pcmBuffers) {
            for (s in buffer) {
                floatBuf[writeOffset++] = s / 32768.0f
            }
        }

        // --- Pass 1: estimate an adaptive RMS gate from this recording's own noise floor,
        // instead of relying on one fixed threshold across every mic/room. Cheap: only
        // computes RMS per window, no chroma/pitch extraction yet. ---
        val windowRmsValues = mutableListOf<Float>()
        run {
            var probeOffset = 0
            while (probeOffset + windowSize <= floatBuf.size) {
                floatBuf.copyInto(window, 0, probeOffset, probeOffset + windowSize)
                windowRmsValues.add(calculateRms(window))
                probeOffset += stepSize
            }
        }
        val adaptiveThreshold = if (windowRmsValues.isNotEmpty()) {
            val sorted = windowRmsValues.sorted()
            val floorIdx = (sorted.size * NOISE_FLOOR_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)
            val noiseFloor = sorted[floorIdx]
            (noiseFloor * NOISE_FLOOR_MULTIPLIER).coerceIn(MIN_ADAPTIVE_RMS_THRESHOLD, MAX_ADAPTIVE_RMS_THRESHOLD)
        } else {
            0.015f
        }
        Log.d(TAG, "Adaptive vocal RMS threshold: $adaptiveThreshold (${windowRmsValues.size} probe windows)")

        // --- Pass 2: real chroma/pitch extraction, gated by the adaptive threshold above ---
        var offset = 0
        while (offset + windowSize <= floatBuf.size) {
            floatBuf.copyInto(window, 0, offset, offset + windowSize)
            val rms = calculateRms(window)
            frameCount++

            if (rms > adaptiveThreshold) {
                val chroma = detector.analyzeChroma(window)

                // Debug: Check if chroma is valid
                val chromaSum = chroma.sum()
                if (chromaSum > 0.01f) {
                    val frameChroma = FloatArray(12)
                    for (i in 0 until 12) {
                        allChroma[i] += chroma[i]
                        frameChroma[i] = chroma[i]
                    }
                    timeFrameChromaList.add(frameChroma)

                    val pitchMidi = estimatePitchAutocorrelation(window, sampleRate)
                    if (pitchMidi in 36..88) {
                        pitchMidiList.add(pitchMidi)
                    }
                }
            }
            offset += stepSize
        }

        window.fill(0f)

        // Debug logging
        Log.d(TAG, "Processed $frameCount frames")
        Log.d(TAG, "Detected ${pitchMidiList.size} pitch values")
        Log.d(TAG, "Chroma sum: ${allChroma.sum()}")
        
        if (pitchMidiList.isEmpty()) {
            Log.e(TAG, "No pitches detected! Using fallback analysis.")
            return createFallbackAnalysis()
        }

        // Log pitch range
        val sortedPitches = pitchMidiList.sorted()
        Log.d(TAG, "Pitch range: ${sortedPitches.first()} - ${sortedPitches.last()}")
        Log.d(TAG, "Pitch distribution: ${sortedPitches.joinToString(",")}")

        // 1. Determine Sung Key
        val (sungRoot, isMinor) = detectDynamicKeyFromChroma(allChroma)
        Log.d(TAG, "Detected key: $sungRoot, isMinor: $isMinor")
        
        val rawSungKey = if (isMinor) "$sungRoot Minor" else "$sungRoot Major"

        // 2. Determine Vocal Statistics
        val lowestMidi = sortedPitches.firstOrNull() ?: 60
        val highestMidi = sortedPitches.lastOrNull() ?: 72
        val medianMidi = if (sortedPitches.isNotEmpty()) sortedPitches[sortedPitches.size / 2] else 66

        val lowestNoteStr = midiToNoteName(lowestMidi)
        val highestNoteStr = midiToNoteName(highestMidi)
        val medianNoteStr = midiToNoteName(medianMidi)

        // 3. Voice Type Classification
        val voiceType = classifyVoiceType(lowestMidi, highestMidi, medianMidi)

        // 4. Transposition
        val targetMedianMidi = 58
        val rawOffset = (targetMedianMidi - medianMidi).coerceIn(-6, 6)
        val finalOffset = manualTranspositionOffset ?: calculateOptimalKeyShift(rawOffset, sungRoot, isMinor)

        val sungRootIndex = NOTE_NAMES.indexOf(sungRoot).coerceAtLeast(0)
        val suggestedRootIndex = ((sungRootIndex + finalOffset) % 12 + 12) % 12
        val suggestedRoot = NOTE_NAMES[suggestedRootIndex]
        val suggestedKeyName = if (isMinor) "$suggestedRoot Minor" else "$suggestedRoot Major"

        // Rest of the function remains the same...
        val comfortAssessment = generateComfortAssessment(
            sungKey = rawSungKey,
            suggestedKey = suggestedKeyName,
            offset = finalOffset,
            highestMidi = highestMidi,
            lowestMidi = lowestMidi,
            voiceType = voiceType
        )

        val diatonicChords = generateDiatonicChords(suggestedRoot, isMinor)
        val capoGuide = generateCapoGuide(suggestedRoot)

        val recognizedProgression = extractDynamicProgressionFromFrames(
            frames = timeFrameChromaList,
            sungRoot = sungRoot,
            suggestedRoot = suggestedRoot,
            isMinor = isMinor
        )

        val progressions = mutableListOf<VocalProgression>()
        if (recognizedProgression != null) {
            progressions.add(recognizedProgression)
        }
        progressions.addAll(generateProgressions(suggestedRoot, isMinor))

        if (progressions.isEmpty()) {
            progressions.add(createFallbackProgression(suggestedRoot, isMinor))
        }

        return VocalAnalysisResult(
            sungKey = rawSungKey,
            suggestedKey = suggestedKeyName,
            transpositionOffset = finalOffset,
            lowestNote = lowestNoteStr,
            highestNote = highestNoteStr,
            medianNote = medianNoteStr,
            voiceType = voiceType,
            comfortAssessment = comfortAssessment,
            diatonicChords = diatonicChords,
            capoGuide = capoGuide,
            commonProgressions = progressions.distinctBy { it.romanNumerals }
        )
    }

    private fun detectDynamicKeyFromChroma(chroma: FloatArray): Pair<String, Boolean> {
        var maxCorr = -2.0
        var bestRoot = "C"
        var bestIsMinor = false

        val sumChroma = chroma.sum().toDouble()
        Log.d(TAG, "Chroma sum for key detection: $sumChroma")

        if (sumChroma <= 0.001) {
            Log.w(TAG, "Chroma sum too small, returning default C")
            return "C" to false
        }

        val normChroma = DoubleArray(12) { chroma[it] / sumChroma }
        Log.d(TAG, "Normalized chroma: ${normChroma.joinToString(", ") { "%.3f".format(it) }}")

        for (rootIdx in 0 until 12) {
            // Test Major
            val majCorr = computeProfileCorrelation(normChroma, MAJOR_PROFILE, rootIdx)
            
            // Test Minor (relative minor - 3 semitones down)
            val minIdx = (rootIdx - 3 + 12) % 12
            val minCorr = computeProfileCorrelation(normChroma, MINOR_PROFILE, minIdx)
            
            Log.d(TAG, "Root: ${NOTE_NAMES[rootIdx]}, Major: ${"%.3f".format(majCorr)}, Minor: ${"%.3f".format(minCorr)}")

            if (majCorr > maxCorr) {
                maxCorr = majCorr
                bestRoot = NOTE_NAMES[rootIdx]
                bestIsMinor = false
            }
            
            if (minCorr > maxCorr) {
                maxCorr = minCorr
                bestRoot = NOTE_NAMES[rootIdx]
                bestIsMinor = true
            }
        }

        Log.d(TAG, "Best key: $bestRoot (${if (bestIsMinor) "Minor" else "Major"}) with correlation: ${"%.3f".format(maxCorr)}")
        
        // If correlation is weak, default to C
        if (maxCorr < 0.1) {
            Log.w(TAG, "Weak correlation detected (${"%.3f".format(maxCorr)}), defaulting to C")
            return "C" to false
        }

        return bestRoot to bestIsMinor
    }

    private fun computeProfileCorrelation(chroma: DoubleArray, profile: DoubleArray, rootShift: Int): Double {
        val shiftedChroma = DoubleArray(12) { chroma[(it + rootShift) % 12] }
        val meanChroma = shiftedChroma.average()
        val meanProfile = profile.average()

        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0

        for (i in 0 until 12) {
            val cDiff = shiftedChroma[i] - meanChroma
            val pDiff = profile[i] - meanProfile
            num += cDiff * pDiff
            den1 += cDiff * cDiff
            den2 += pDiff * pDiff
        }

        if (den1 <= 0.0 || den2 <= 0.0) return -1.0
        val correlation = num / (sqrt(den1) * sqrt(den2))
        return correlation
    }

    // ... Rest of the functions remain the same as before ...
    
    private fun calculateRms(buffer: FloatArray): Float {
        var sum = 0f
        for (v in buffer) sum += v * v
        return sqrt(sum / buffer.size)
    }

    private fun estimatePitchAutocorrelation(window: FloatArray, sampleRate: Int): Int {
        val minHz = 75f
        val maxHz = 1000f
        val minLag = (sampleRate / maxHz).toInt()
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(window.size - 1)

        var bestLag = -1
        var maxCorr = 0f

        for (lag in minLag..maxLag) {
            var corr = 0f
            for (i in 0 until (window.size - lag) step 4) {
                corr += window[i] * window[i + lag]
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        if (bestLag > 0 && maxCorr > 0.1f) {
            val freq = sampleRate.toFloat() / bestLag
            if (freq in minHz..maxHz) {
                val midi = 69f + 12f * log2(freq / 440f)
                return midi.roundToInt().coerceIn(36, 88)
            }
        }
        return -1
    }

    private fun classifyVoiceType(lowestMidi: Int, highestMidi: Int, medianMidi: Int): String {
        return when {
            lowestMidi < 45 -> "Bass (Very Low)"
            lowestMidi in 45..52 -> "Baritone / Bass-Baritone"
            lowestMidi in 53..58 -> "Tenor / Baritone"
            lowestMidi in 59..65 && medianMidi < 65 -> "Contralto / Alto"
            lowestMidi in 60..68 && medianMidi in 64..72 -> "Mezzo-Soprano"
            lowestMidi > 68 -> "Soprano (High Range)"
            else -> "Unclassified (Possibly Child/Untrained)"
        }
    }

    private fun calculateOptimalKeyShift(rawShift: Int, sungRoot: String, isMinor: Boolean): Int {
        val sungIdx = NOTE_NAMES.indexOf(sungRoot).coerceAtLeast(0)
        val candidateIdx = ((sungIdx + rawShift) % 12 + 12) % 12
        val candidateNote = NOTE_NAMES[candidateIdx]

        if (candidateNote.contains("#")) {
            return if (rawShift < 0) rawShift - 1 else rawShift + 1
        }
        return rawShift
    }

    private fun generateComfortAssessment(
        sungKey: String,
        suggestedKey: String,
        offset: Int,
        highestMidi: Int,
        lowestMidi: Int,
        voiceType: String
    ): String {
        val highestNoteStr = midiToNoteName(highestMidi)
        val lowestNoteStr = midiToNoteName(lowestMidi)

        return when {
            offset == 0 -> "Your singing key ($sungKey) is ideally suited for your $voiceType vocal range! No transposition is required. Your range spans $lowestNoteStr to $highestNoteStr."
            offset < 0 -> "High vocal strain detected (peak note $highestNoteStr). Transposing down ${abs(offset)} semitones to $suggestedKey relaxes the melody into your sweet-spot vocal range."
            else -> "Low register vocal strain detected (lowest note $lowestNoteStr). Transposing up $offset semitones to $suggestedKey elevates the vocal melody for maximum warmth and projection."
        }
    }

    private fun generateDiatonicChords(root: String, isMinor: Boolean): List<ChordInfo> {
        val rootIdx = NOTE_NAMES.indexOf(root).coerceAtLeast(0)
        fun noteAt(semitones: Int): String {
            val safeSemitone = ((semitones % 12) + 12) % 12
            return NOTE_NAMES[(rootIdx + safeSemitone) % 12]
        }

        val diatonicNames = if (!isMinor) {
            listOf(
                noteAt(0),
                "${noteAt(2)}m",
                "${noteAt(4)}m",
                noteAt(5),
                noteAt(7),
                "${noteAt(9)}m",
                "${noteAt(11)}dim"
            )
        } else {
            listOf(
                "${noteAt(0)}m",
                "${noteAt(2)}dim",
                noteAt(3),
                "${noteAt(5)}m",
                "${noteAt(7)}m",
                noteAt(8),
                noteAt(10)
            )
        }

        return diatonicNames.mapNotNull { name ->
            val chordType = when {
                name.contains("dim") || name.contains("°") -> "Diminished"
                name.contains("m") -> "Minor"
                else -> "Major"
            }
            
            detector.chordDatabase.find { it.name == name } ?: ChordInfo(
                name = name,
                rootNote = name.replace("m", "").replace("dim", "").replace("°", ""),
                chordType = chordType,
                notes = listOf(name),
                fretDiagram = com.example.audio.FretDiagram(intArrayOf(-1, 0, 2, 2, 1, 0)),
                description = "Diatonic chord $name in key of $root"
            )
        }
    }

    private fun generateCapoGuide(root: String): String {
        return when (root) {
            "C" -> "No Capo required! Play open C Major shapes directly."
            "C#", "D♭" -> "Capo 1st Fret using open C Major shapes."
            "D" -> "Capo 2nd Fret using open C Major shapes OR play open D Major shapes."
            "D#", "E♭" -> "Capo 1st Fret using open D Major shapes or Capo 3rd Fret with C shapes."
            "E" -> "No Capo required! Play open E Major shapes."
            "F" -> "Capo 1st Fret using open E Major shapes."
            "F#", "G♭" -> "Capo 2nd Fret using open E Major shapes or Capo 4th Fret with C shapes."
            "G" -> "No Capo required! Play open G Major shapes."
            "G#", "A♭" -> "Capo 1st Fret using open G Major shapes."
            "A" -> "No Capo required! Play open A Major shapes or Capo 2nd Fret with G shapes."
            "A#", "B♭" -> "Capo 1st Fret using open A Major shapes or Capo 3rd Fret with G shapes."
            "B" -> "Capo 2nd Fret using open A Major shapes or Capo 4th Fret with G shapes."
            else -> "Capo position varies by preferred open chord shapes. For key of $root, experiment with capo on frets 1-4."
        }
    }

    private fun generateProgressions(root: String, isMinor: Boolean): List<VocalProgression> {
        val rootIdx = NOTE_NAMES.indexOf(root).coerceAtLeast(0)
        fun noteAt(semitones: Int): String {
            val safeSemitone = ((semitones % 12) + 12) % 12
            return NOTE_NAMES[(rootIdx + safeSemitone) % 12]
        }

        return if (!isMinor) {
            listOf(
                VocalProgression("Pop 4-Chord Standard", "I – V – vi – IV", 
                    listOf(noteAt(0), noteAt(7), "${noteAt(9)}m", noteAt(5))),
                VocalProgression("Acoustic Ballad", "I – vi – IV – V",
                    listOf(noteAt(0), "${noteAt(9)}m", noteAt(5), noteAt(7))),
                VocalProgression("Emotional Chorus / R&B", "vi – IV – I – V",
                    listOf("${noteAt(9)}m", noteAt(5), noteAt(0), noteAt(7))),
                VocalProgression("Classic Folk Verse", "I – IV – V – I",
                    listOf(noteAt(0), noteAt(5), noteAt(7), noteAt(0)))
            )
        } else {
            listOf(
                VocalProgression("Pop Minor Standard", "i – VI – III – VII",
                    listOf("${noteAt(0)}m", noteAt(8), noteAt(3), noteAt(10))),
                VocalProgression("Moody Ballad", "i – iv – v – i",
                    listOf("${noteAt(0)}m", "${noteAt(5)}m", "${noteAt(7)}m", "${noteAt(0)}m")),
                VocalProgression("Dramatic Verse", "i – VI – iv – v",
                    listOf("${noteAt(0)}m", noteAt(8), "${noteAt(5)}m", "${noteAt(7)}m"))
            )
        }
    }

    private fun extractDynamicProgressionFromFrames(
        frames: List<FloatArray>,
        sungRoot: String,
        suggestedRoot: String,
        isMinor: Boolean
    ): VocalProgression? {
        if (frames.size < 8) return null

        val frameWindowSize = (frames.size / 4).coerceAtLeast(2)
        val detectedSungChords = mutableListOf<String>()

        for (section in 0 until 4) {
            val startIdx = section * frameWindowSize
            val endIdx = ((section + 1) * frameWindowSize).coerceAtMost(frames.size)
            if (startIdx >= endIdx) break

            val sectionChroma = FloatArray(12)
            for (f in startIdx until endIdx) {
                val frame = frames[f]
                for (i in 0 until 12) {
                    sectionChroma[i] += frame.getOrElse(i) { 0f }
                }
            }

            var maxVal = 0f
            var maxIdx = 0
            for (i in 0 until 12) {
                if (sectionChroma[i] > maxVal) {
                    maxVal = sectionChroma[i]
                    maxIdx = i
                }
            }
            if (maxVal > 0) {
                detectedSungChords.add(NOTE_NAMES[maxIdx])
            }
        }

        if (detectedSungChords.isEmpty()) return null

        val sungRootIdx = NOTE_NAMES.indexOf(sungRoot).coerceAtLeast(0)
        val suggestedRootIdx = NOTE_NAMES.indexOf(suggestedRoot).coerceAtLeast(0)

        val romanNumerals = mutableListOf<String>()
        val suggestedChordNames = mutableListOf<String>()

        for (note in detectedSungChords) {
            val noteIdx = NOTE_NAMES.indexOf(note).coerceAtLeast(0)
            val sungInterval = ((noteIdx - sungRootIdx) % 12 + 12) % 12

            val degreeRoman = getDegreeRoman(sungInterval, isMinor)
            romanNumerals.add(degreeRoman.first)

            val transposedNoteIdx = ((suggestedRootIdx + sungInterval) % 12 + 12) % 12
            val chordName = NOTE_NAMES[transposedNoteIdx] + degreeRoman.second
            suggestedChordNames.add(chordName)
        }

        return VocalProgression(
            title = "Recognized Melody Pattern",
            romanNumerals = romanNumerals.joinToString(" – "),
            chordNames = suggestedChordNames,
            isRecognizedFromRecording = true
        )
    }

    private fun getDegreeRoman(interval: Int, isMinor: Boolean): Pair<String, String> {
        val norm = ((interval % 12) + 12) % 12
        
        return when (norm) {
            0 -> if (isMinor) "i" to "m" else "I" to ""
            1 -> if (isMinor) "ii°" to "dim" else "II" to ""
            2 -> if (isMinor) "iii" to "m" else "ii" to "m"
            3 -> if (isMinor) "iv" to "m" else "III" to ""
            4 -> if (isMinor) "v" to "m" else "IV" to ""
            5 -> if (isMinor) "VI" to "" else "V" to ""
            6 -> if (isMinor) "vii°" to "dim" else "vi" to "m"
            7 -> if (isMinor) "I" to "" else "VII" to ""
            8 -> if (isMinor) "II" to "" else "I" to ""
            9 -> if (isMinor) "iii" to "m" else "ii" to "m"
            10 -> if (isMinor) "iv" to "m" else "III" to ""
            11 -> if (isMinor) "v" to "m" else "IV" to ""
            else -> "I" to ""
        }
    }

    private fun createFallbackAnalysis(): VocalAnalysisResult {
        return VocalAnalysisResult(
            sungKey = "C Major",
            suggestedKey = "C Major",
            transpositionOffset = 0,
            lowestNote = "C4",
            highestNote = "C5",
            medianNote = "C4",
            voiceType = "Unclassified (Insufficient Data)",
            comfortAssessment = "Unable to analyze vocal range. Please record a clearer audio sample.",
            diatonicChords = generateDiatonicChords("C", false),
            capoGuide = "No capo required.",
            commonProgressions = listOf(
                VocalProgression(
                    title = "Default Progression",
                    romanNumerals = "I – IV – V – I",
                    chordNames = listOf("C", "F", "G", "C"),
                    isRecognizedFromRecording = false
                )
            )
        )
    }

    private fun createFallbackProgression(root: String, isMinor: Boolean): VocalProgression {
        val rootIdx = NOTE_NAMES.indexOf(root).coerceAtLeast(0)
        fun noteAt(semitones: Int): String {
            val safeSemitone = ((semitones % 12) + 12) % 12
            return NOTE_NAMES[(rootIdx + safeSemitone) % 12]
        }

        return if (!isMinor) {
            VocalProgression(
                title = "Basic I-IV-V Progression",
                romanNumerals = "I – IV – V – I",
                chordNames = listOf(noteAt(0), noteAt(5), noteAt(7), noteAt(0)),
                isRecognizedFromRecording = false
            )
        } else {
            VocalProgression(
                title = "Basic i-iv-v Progression",
                romanNumerals = "i – iv – v – i",
                chordNames = listOf("${noteAt(0)}m", "${noteAt(5)}m", "${noteAt(7)}m", "${noteAt(0)}m"),
                isRecognizedFromRecording = false
            )
        }
    }
}