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
