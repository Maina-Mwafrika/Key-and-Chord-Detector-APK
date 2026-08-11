package com.example.audio

enum class DetectionInputMode {
    MICROPHONE,
    FILE,
    YOUTUBE
}

enum class DetectionStatus {
    IDLE,
    LISTENING,
    PROCESSING,
    DETECTED,
    ERROR
}

/**
 * Controls how uploaded songs are chord-matched:
 * - SIMPLE: the chord for each measure is matched only against the diatonic chords of the
 *   song's detected key (so results always "make sense" within the key).
 * - ADVANCED: the full chord vocabulary is available, so borrowed/chromatic chords outside
 *   the diatonic set can be detected.
 */
enum class ChordComplexityMode {
    SIMPLE,
    ADVANCED
}

/**
 * Represents guitar string frets: 6th string (Low E) to 1st string (High E).
 * -1 = Muted/Don't play, 0 = Open string, 1..12 = Fret number
 */
data class FretDiagram(
    val frets: IntArray, // 6 elements: [E2, A2, D3, G3, B3, E4]
    val fingerings: IntArray = intArrayOf(0, 0, 0, 0, 0, 0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FretDiagram
        return frets.contentEquals(other.frets) && fingerings.contentEquals(other.fingerings)
    }

    override fun hashCode(): Int {
        var result = frets.contentHashCode()
        result = 31 * result + fingerings.contentHashCode()
        return result
    }
}

data class ChordInfo(
    val name: String,
    val rootNote: String,
    val chordType: String, // "Major", "Minor", "7th", "5th (Power)", etc.
    val notes: List<String>,
    val fretDiagram: FretDiagram,
    val description: String = ""
)

data class DetectionResult(
    val chord: ChordInfo? = null,
    val confidence: Float = 0f, // 0.0 to 1.0
    val estimatedKey: String? = null, // e.g. "C Major", "A Minor"
    val chromaVector: FloatArray = FloatArray(12), // C, C#, D, D#, E, F, F#, G, G#, A, A#, B
    val stringEnergies: FloatArray = FloatArray(6), // E2, A2, D3, G3, B3, E4
    val waveform: FloatArray = FloatArray(64),
    val status: DetectionStatus = DetectionStatus.IDLE,
    val errorMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DetectionResult
        return chord == other.chord &&
                confidence == other.confidence &&
                estimatedKey == other.estimatedKey &&
                chromaVector.contentEquals(other.chromaVector) &&
                stringEnergies.contentEquals(other.stringEnergies) &&
                waveform.contentEquals(other.waveform) &&
                status == other.status &&
                errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = chord?.hashCode() ?: 0
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (estimatedKey?.hashCode() ?: 0)
        result = 31 * result + chromaVector.contentHashCode()
        result = 31 * result + stringEnergies.contentHashCode()
        result = 31 * result + waveform.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

data class AudioFileMetadata(
    val fileName: String,
    val durationMs: Long,
    val fileSizeFormatted: String,
    val uriString: String
)

data class ChordHistoryItem(
    val chordName: String,
    val timestampFormatted: String,
    val confidencePercent: Int,
    val inputSource: String,
    val notes: List<String>
)

/**
 * The winning chord for a single measure of an offline-analyzed song, plus the time range
 * it covers and how confidently it matched.
 */
data class MeasureChord(
    val measureIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val chord: ChordInfo?,
    val confidence: Float
)

/**
 * The full result of SongAnalyzer's one-time, offline analysis of an uploaded song: a
 * single overall key plus a chord for every measure. Playback looks up the current chord
 * by position in this timeline instead of re-running live detection, so display is
 * perfectly smooth and synced.
 */
data class SongChordTimeline(
    val key: String,
    val bpm: Int,
    val beatsPerMeasure: Int,
    val measureDurationMs: Long,
    val measures: List<MeasureChord>,
    val totalDurationMs: Long,
    val chordMode: ChordComplexityMode
) {
    fun measureIndexAt(positionMs: Long): Int {
        if (measureDurationMs <= 0 || measures.isEmpty()) return 0
        val idx = (positionMs / measureDurationMs).toInt()
        return idx.coerceIn(0, measures.size - 1)
    }
}

/**
 * The previous / current / next chord to show in the carousel-style chord visualization.
 * For live/streaming input (no precomputed timeline), "next" is unknowable and stays null.
 */
data class ChordCarouselState(
    val previous: ChordInfo? = null,
    val current: ChordInfo? = null,
    val next: ChordInfo? = null,
    val measureIndex: Int = -1
)

/**
 * Tracks the progress of SongAnalyzer's offline pre-processing of an uploaded file.
 */
sealed class SongAnalysisState {
    object Idle : SongAnalysisState()
    data class Analyzing(val progress: Float, val stage: String = "Analyzing") : SongAnalysisState()
    object Ready : SongAnalysisState()
    data class Error(val message: String) : SongAnalysisState()
}