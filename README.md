# Claudian Call

Native Android companion app (Kotlin) for **screen-off, phone-call-style** voice conversations with your workstation's `claudian-bridge` over Tailscale.

## Why This Exists

Android suspends Obsidian's WebView and revokes `getUserMedia()` microphone capture when the screen turns off. **Claudian Call** runs a native Android Foreground Service so you can lock your phone, put it in your pocket or use a Bluetooth headset, and talk to AI hands-free just like a normal phone call.

## Features

- **Adaptive App Icon & Flagship Call UI**: Custom vector adaptive icon (`ic_launcher`), edge-to-edge safe insets, active provider/model status badge, tap-to-interrupt button during speech, haptic call dock, and monochrome call notification icon (`ic_stat_call`).
- **Screen-Off Foreground Service** (`microphone | mediaPlayback`): Keeps CPU (`PARTIAL_WAKE_LOCK`), Wi-Fi (`WifiLock`), mic capture, and Tailscale SSE streaming alive with the screen off.
- **2-Layer Noise-Resistant Barge-In (`SpeechVad.kt`)**:
  - **Layer 1 (DSP)**: `215 Hz – 3.4 kHz` speech-band filter, Zero-Crossing Rate gate (`0.02..0.32`), and adaptive ambient noise floor tracking.
  - **Layer 2 (Neural VAD)**: On-device **Silero VAD v6** (`silero_vad_v6.onnx` via `onnxruntime-android` with `ALL_OPT` and `allow_spinning=0` for Tensor G5 / Pixel 10 Pro XL efficiency), requiring 5 consecutive speech frames (~160 ms) to interrupt TTS.
- **Hardware Acoustic Echo Cancellation & Routing**: Uses `AudioRecord` (`VOICE_COMMUNICATION`) with `AcousticEchoCanceler` + `NoiseSuppressor`, ~256 ms pre-roll ring buffer, and `AudioTrack` (`USAGE_VOICE_COMMUNICATION`) with BLE Audio / Bluetooth SCO / Earpiece / Speakerphone routing.
- **Multi-Provider, Workspaces & Dynamic Models**: Switch between **Claude Code** (`claude`), **Antigravity** (`agy`), and **OpenCode** (`opencode`), pick or create project folders (`📁 <workspace> ▾` via `GET/POST /workspaces`), and select models dynamically from `GET /models?provider=...`.
- **Non-Interrupting Turn Queue**: Captures full utterances with `1800 ms` silence tolerance, `600 ms` minimum speech, and 3-frame (~96 ms) speech-onset debouncing; **Barge-In** is disabled by default (`bargeIn = false`), and follow-up utterances queue while the agent is thinking instead of aborting the active harness turn.
- **Session Isolation, 5s Recap & Save to Vault**: Start clean threads with `＋`, switch workspace-scoped conversations (`New conversation (N) ▾`), hear a 5-second catch-up via **`↻ Recap`**, and save notes or individual bubbles to your Obsidian vault via **`Save`** (or long-press).
- **Live Tool Progress & Hybrid Text Input**: Real-time tool status (`Searching web…`, `Reading file…`) in the call card and lock-screen notification, plus a text/paste input bar for exact symbols or error logs mid-call.
- **In-App Self-Update**: Automatically checks `GET /apk/version` on startup (and via **Check for App Update** in Settings), downloads `claudian-call.apk` with progress, and installs via Android `FileProvider`.
- **Notification Controls**: Mute/Unmute, Speaker/Earpiece, and Hang Up directly from the lock screen or notification shade.
- **Shared Cross-Provider Sessions**: Lists and resumes conversations across **Claude**, **Antigravity**, and **OpenCode** scoped by project folder.

## 2-Step Quick Start

1. **Install Claudian Call on your Android phone** (from F-Droid, [GitHub Releases](https://github.com/HerdingAI/claudian-call/releases), or `http://<your-bridge-host>:8790/apk`).
2. **Run `claudian-bridge` on your PC:**
   ```bash
   git clone https://github.com/HerdingAI/claudian-bridge.git
   cd claudian-bridge
   ./setup.sh
   ```
   `./setup.sh` auto-installs `faster-whisper` STT, Kokoro TTS + voice models, starts the bridge with LAN mDNS auto-discovery (`_claudian._tcp.`), and prints a **QR code** (`claudian://pair?...`).

3. **Pair in 1 Tap:** Scan the QR code from your phone's camera to open **Claudian Call** pre-configured, or open **⚙ Settings → Auto-Discover Bridge on LAN** (on the same Wi-Fi) / paste your Tailscale URL & Auth Token, then tap **Call**!

## Build from Source

Requires JDK 17 (full JDK with `jlink`, e.g., `~/.gradle/jdks/jdk-17.0.21+9`) and Android SDK (API 34):

```bash
JAVA_HOME=~/.gradle/jdks/jdk-17.0.21+9 ./gradlew assembleFdroidRelease   # F-Droid / FOSS build (no self-updater)
JAVA_HOME=~/.gradle/jdks/jdk-17.0.21+9 ./gradlew assembleDirectDebug     # Direct sideload build (includes /apk self-updater)
```

Current direct build: **`v0.6.9` (`versionCode = 15`)**. Output APKs: `app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk` and `app/build/outputs/apk/direct/debug/app-direct-debug.apk` (release copy in `dist/claudian-call.apk`).

## Claudian Suite

- **[Claudian Bridge](https://github.com/HerdingAI/claudian-bridge)** — Workstation HTTP/SSE server required by Claudian Call (exposes Claude Code, Antigravity, OpenCode, `faster-whisper` STT, and Kokoro TTS).
- **[Claudian Mobile](https://github.com/HerdingAI/claudian-mobile)** — Mobile-compatible Obsidian plugin for iOS, Android, and Desktop over `claudian-bridge`.
- **[Claudian Voice](https://github.com/HerdingAI/claudian-voice)** — Desktop Obsidian plugin adding local `faster-whisper` STT and Kokoro TTS directly to Claudian.

## License

MIT — see [LICENSE](LICENSE).
