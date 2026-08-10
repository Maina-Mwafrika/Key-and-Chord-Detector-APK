# Real-Time Polyphonic Chord Detector & Key Analyzer

An advanced, real-time Android chord detection and musical key analysis application built with **Kotlin**, **Jetpack Compose**, **Coroutines**, and **Digital Signal Processing (DSP)** algorithms.

---

## 🌟 Key Features

- **Polyphonic DSP Chord Detection**:
  - Uses 12-bin Chromagram spectrum analysis (mapping FFT semitones from $C$ to $B$) to identify major, minor, 7th, and power chords ($C, G, Am, F, Em, D7, E5, A7$, etc.) unbiasedly.
  - Features smooth transition debouncing and hysteresis tracking (~120ms window) to eliminate chord jumping during transitions.
  
- **Musical Key Estimation**:
  - Employs the **Krumhansl-Schmuckler Key Profiling Algorithm** using Pearson correlation against 24 major and minor key profiles to detect the likely musical key of a track (e.g., *Key of G Major*, *Key of Am (Minor)*).

- **Multi-Input Audio Engine**:
  1. **Microphone**: Real-time ambient acoustic audio capture via `AudioRecord` (44.1 kHz PCM).
  2. **Local Audio Files**: Decodes MP3, WAV, M4A, and OGG files via `MediaCodec` and `MediaExtractor`, playing audio continuously through hardware speakers via `AudioTrack`.
  3. **YouTube Audio Stream / Presets**: Real-time acoustic guitar audio synthesis with harmonic overtones and exponential pluck decay envelopes played live out loud.

- **Immersive Visual Interfaces**:
  - **Animated 6-String Guitar Visualizer**: Displays vibrating string energy levels ($E_2, A_2, D_3, G_3, B_3, E_4$) and highlights active string notes.
  - **Waveform & Chromagram Spectrum**: Live oscilloscope display paired with a 12-semitone pitch class energy bar chart.
  - **Interactive Fretboard Diagrams**: Popup fingering box charts showing mute ($X$), open ($O$), and fret numbers for identified chords.
  - **Instant Synthetic Chord Generator**: Quick-access test buttons to trigger acoustic chord audio and test the detection pipeline.
  - **Chord History Log**: Tracks identified chords with timestamps, confidence scores, and constituent note breakdowns.

---

## 🛠 Architecture & Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material Design 3
- **Architecture**: MVVM (Model-View-ViewModel) + Clean DSP Architecture
- **Audio Output**: Android `AudioTrack` (PCM 16-bit continuous stream)
- **Audio Capture / Processing**: `AudioRecord`, `MediaCodec`, `MediaExtractor`, Custom FFT/Goertzel Filterbanks

---

## 🚀 Building & Running

### Prerequisites
- **Android Studio**: Ladybug / Jellyfish or newer
- **JDK**: JDK 17
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)

### Build Instructions

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/chord-detector-android.git
   cd chord-detector-android
   ```

2. **Open in Android Studio** and sync Gradle.

3. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Run Unit Tests**:
   ```bash
   ./gradlew test
   ```

---

## 📜 Permissions

The app declares the following permissions in `AndroidManifest.xml`:
- `android.permission.RECORD_AUDIO`: For real-time microphone chord analysis.
- `android.permission.INTERNET`: For streaming audio presets and metadata.
- `android.permission.READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO`: For loading local audio files.

---

## 📄 License

This project is licensed under the MIT License - see the `LICENSE` file for details.
