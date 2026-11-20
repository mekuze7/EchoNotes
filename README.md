# 🎙️ EchoNotes — AI Voice Recorder & Meeting Intelligence

![Android](https://img.shields.io/badge/Platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)
![Gemini AI](https://img.shields.io/badge/AI-Google%20Gemini-orange.svg)
![License](https://img.shields.io/badge/License-MIT-brightgreen.svg)

**EchoNotes** is a modern, privacy-focused Android application designed for high-fidelity voice recording, real-time speech-to-text, multi-speaker diarization, and AI-powered meeting synthesis. Powered by **Jetpack Compose**, **Google Gemini AI**, and **Android AudioFX hardware DSP**.

---

## ✨ Key Features

### 🎙️ Advanced Audio Capture & Noise Avoidance
* **Far-Field Voice Boost:** Microphone tuned with `MediaRecorder.AudioSource.VOICE_RECOGNITION` to capture remote speakers and quiet voices in large rooms.
* **Hardware Noise Avoidance:** Active hardware noise suppression (`NoiseSuppressor`), Automatic Gain Control (`AutomaticGainControl`), and Acoustic Echo Cancellation (`AcousticEchoCanceler`).
* **Visual Waveform Canvas:** Live neon amplitude equalizer visualizer during recording and playback.

### 🗣️ Multi-Speaker Diarization ("Who Said What")
* **Speaker Identification:** Automatically detects multiple speakers in meetings, interviews, and panel discussions.
* **Styled Dialogue Badges:** Renders distinct speaker turns with color-coded badges (`🗣️ Speaker 1`, `🗣️ Speaker 2`, `🗣️ Interviewer`).

### 🧠 Gemini AI Synthesis & Action Items
* **Executive Summary:** Synthesizes 1-3 paragraph high-level overviews of recordings.
* **Key Takeaways:** Extracts bulleted decision points and key ideas.
* **Checkable Action Items:** Automatically extracts tasks with assigned due dates (`YYYY-MM-DD`).

### 💬 Interactive "Chat with Note"
* Talk directly with your voice note transcript! Ask questions about specific meeting points, decisions, or speaker statements.

### 🌐 Live Subtitles & Translation
* Real-time live speech-to-text streaming with optional translation between **English** and **Amharic (Ethiopic/Ge'ez script)**.

### 📤 Multi-Format Export & Sharing
* **PDF Document Export:** Formatted A4 PDF documents with title, metadata, executive summary, takeaways, checkable action items, and full transcript.
* **Markdown (.md) & Plain Text (.txt):** Instant markdown or plain text export.
* **Audio File Sharing (.m4a):** Securely share raw voice recordings via Android `FileProvider`.

### ⚡ Offline-First Architecture
* Fully functional offline with local **Room SQLite Database** and local rule-based NLP fallback intelligence when internet or API keys are unavailable.

---

## 🛠️ Tech Stack & Architecture

* **Language:** 100% Idiomatic Kotlin
* **UI Framework:** Jetpack Compose (Google Stitch Modern Dark Theme)
* **Architecture:** MVVM + Clean Architecture + `StateFlow` + Kotlin Coroutines
* **Database:** Room Persistence Library (SQLite)
* **Audio Engine:** `MediaRecorder`, `AudioTrack`, `AudioFX` (`NoiseSuppressor`, `AGC`, `AEC`)
* **AI Engine:** Google Gemini Multimodal API (`gemini-1.5-flash`, `gemini-2.0-flash`)
* **Export Engine:** Android Native `PdfDocument`, `FileProvider`

---

## 🚀 Getting Started

### Prerequisites
* **Android Studio** Ladybug (2024.2.1) or newer
* **Android SDK** Level 34+
* **JDK** 17

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/mekuze7/EchoNotes.git
   cd EchoNotes
   ```

2. **Configure Gemini API Key (Optional for Cloud AI):**
   Create a `.env` file in the project root directory:
   ```properties
   GEMINI_API_KEY=AIzaSyYourActualKeyHere
   ```
   *(Note: Free API keys can be obtained from [Google AI Studio](https://aistudio.google.com/app/apikey))*

3. **Build & Run:**
   Open the project in Android Studio, connect your Android device or emulator, and press **Run (▶)**.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
