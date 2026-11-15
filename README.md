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
- **Compound Engineering Workflow Bar & Spoken-First Formatting**: One-tap chips (**General · Brainstorm · Plan · Build · Review · Debug · Compound**) inject `SKILL.md` workflow guidance and spoken-first formatting, with read-only `plan` mode on exploratory stages and full web/MCP tool access.
- **Session Isolation, 5s Recap & Save to Vault**: Start clean threads with `＋`, switch workspace-scoped conversations (`New conversation (N) ▾`), hear a 5-second catch-up via **`↻ Recap`**, and save notes or individual bubbles to your Obsidian vault via **`Save`** (or long-press).
- **Live Tool Progress & Hybrid Text Input**: Real-time tool status (`Searching web…`, `Reading file…`) in the call card and lock-screen notification, plus a text/paste input bar for exact symbols or error logs mid-call.
- **In-App Self-Update**: Automatically checks `GET /apk/version` on startup (and via **Check for App Update** in Settings), downloads `claudian-call.apk` with progress, and installs via Android `FileProvider`.
- **Notification Controls**: Mute/Unmute, Speaker/Earpiece, and Hang Up directly from the lock screen or notification shade.
- **Shared Cross-Provider Sessions**: Lists and resumes conversations across **Claude**, **Antigravity**, and **OpenCode** scoped by project folder.

## Install on Phone

1. Connect your phone and workstation to the same Tailscale tailnet (or LAN) and start `claudian-bridge`.
2. Open `http://<your-bridge-host>:8790/apk` in Chrome to download and install `claudian-call.apk` (currently `v0.6.6`, build `12`).
3. Open **Claudian Call**, tap **⚙**, set **Bridge URL** and **Auth Token** (from `~/.config/claudian-bridge/config.json`), choose your **Provider** & **Model**, and save.
4. Tap **Call** and grant Microphone, Notification, and Bluetooth permissions. Future builds with a higher `versionCode` will offer one-tap in-app updates.

## Build from Source

Requires JDK 17, Android SDK (API 34), and Gradle 8.9+:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
gradle assembleDebug --no-daemon
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk` (packaged release copy in `dist/claudian-call.apk`).

## Claudian Suite

- **[Claudian Bridge](https://github.com/HerdingAI/claudian-bridge)** — Workstation HTTP/SSE server required by Claudian Call (exposes Claude Code, Antigravity, OpenCode, `faster-whisper` STT, and Kokoro TTS).
- **[Claudian Mobile](https://github.com/HerdingAI/claudian-mobile)** — Mobile-compatible Obsidian plugin for iOS, Android, and Desktop over `claudian-bridge`.
- **[Claudian Voice](https://github.com/HerdingAI/claudian-voice)** — Desktop Obsidian plugin adding local `faster-whisper` STT and Kokoro TTS directly to Claudian.

## License

MIT — see [LICENSE](LICENSE).
