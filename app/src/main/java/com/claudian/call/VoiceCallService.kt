package com.claudian.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CallUiState(
    val inCall: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val isMuted: Boolean = false,
    val speakerOn: Boolean = false,
    val sessionId: String? = null,
    val callStartedAtMs: Long = 0L,
    val messages: List<ChatMessage> = emptyList(),
    val statusNote: String = "Ready"
)

class VoiceCallService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState

    private lateinit var api: BridgeApi
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var prevAudioMode: Int = AudioManager.MODE_NORMAL
    private var prevSpeakerOn: Boolean = false
    private var focusRequest: AudioFocusRequest? = null

    private var captureJob: Job? = null
    private var turnJob: Job? = null
    private var activeChatCall: Call? = null
    private val activeTurnId = java.util.concurrent.atomic.AtomicLong(0L)

    private val ttsGeneration = AtomicInteger(0)
    private val ttsQueue = LinkedBlockingQueue<Pair<Int, String>>()
    private var ttsThread: Thread? = null
    private val ttsSpeaking = AtomicBoolean(false)
    @Volatile
    private var activeTrack: AudioTrack? = null
    @Volatile
    private var lastTtsEndedAtMs: Long = 0L

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    override fun onCreate() {
        super.onCreate()
        api = BridgeApi { CallSettings.load(this) }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                startCall(sid)
            }
            ACTION_HANGUP -> stopCall()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
        }
        return START_NOT_STICKY
    }

    fun setSessionContext(sessionId: String?, messages: List<ChatMessage>) {
        _uiState.value = _uiState.value.copy(
            sessionId = sessionId,
            messages = messages.toList()
        )
    }

    fun speakRecap(recapText: String) {
        if (!_uiState.value.inCall || recapText.isBlank()) return
        val gen = ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        stopActiveTrack()
        enqueueSentence(gen, recapText)
    }

    fun resetSession() {
        ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        stopActiveTrack()
        activeChatCall?.cancel()
        activeChatCall = null
        turnJob?.cancel()
        ttsSpeaking.set(false)
        _uiState.value = _uiState.value.copy(
            sessionId = null,
            messages = emptyList(),
            phase = if (_uiState.value.inCall) {
                if (_uiState.value.isMuted) CallPhase.MUTED else CallPhase.LISTENING
            } else CallPhase.IDLE,
            statusNote = if (_uiState.value.inCall) {
                if (_uiState.value.isMuted) "Muted" else "New session · Listening..."
            } else "New session"
        )
        if (_uiState.value.inCall) updateNotification()
    }

    fun startCall(sessionId: String?) {
        if (_uiState.value.inCall) return

        val settings = CallSettings.load(this)
        val initialSid = sessionId ?: _uiState.value.sessionId ?: settings.lastSessionId.ifBlank { null }

        _uiState.value = _uiState.value.copy(
            inCall = true,
            phase = CallPhase.LISTENING,
            isMuted = false,
            speakerOn = settings.speakerOn,
            sessionId = initialSid,
            callStartedAtMs = System.currentTimeMillis(),
            statusNote = "Listening..."
        )

        val notif = buildNotification(_uiState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireLocks()
        configureCallAudio(settings.speakerOn)
        startTtsWorker()
        startCaptureLoop()
    }

    fun stopCall() {
        if (!_uiState.value.inCall) return

        ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        stopActiveTrack()
        activeChatCall?.cancel()
        activeChatCall = null
        turnJob?.cancel()
        turnJob = null
        captureJob?.cancel()
        captureJob = null
        ttsThread?.interrupt()
        ttsThread = null

        releaseAudioEffects()
        restoreAudio()
        releaseLocks()

        _uiState.value = _uiState.value.copy(
            inCall = false,
            phase = CallPhase.IDLE,
            isMuted = false,
            statusNote = "Call ended"
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(
            isMuted = next,
            phase = if (next) CallPhase.MUTED else if (ttsSpeaking.get()) CallPhase.SPEAKING else CallPhase.LISTENING,
            statusNote = if (next) "Muted" else "Listening..."
        )
        updateNotification()
    }

    fun toggleSpeaker() {
        val next = !_uiState.value.speakerOn
        val s = CallSettings.load(this).copy(speakerOn = next)
        CallSettings.save(this, s)
        applyAudioRoute(next)
        _uiState.value = _uiState.value.copy(speakerOn = next)
        updateNotification()
    }

    fun interruptSpeaking() {
        ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        stopActiveTrack()
        activeChatCall?.cancel()
        activeChatCall = null
        turnJob?.cancel()
        ttsSpeaking.set(false)
        if (_uiState.value.inCall) {
            _uiState.value = _uiState.value.copy(
                phase = if (_uiState.value.isMuted) CallPhase.MUTED else CallPhase.LISTENING,
                statusNote = if (_uiState.value.isMuted) "Muted" else "Listening..."
            )
            updateNotification()
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudianCall::VoiceWakeLock").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "ClaudianCall::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {}
        wifiLock = null
    }

    private fun configureCallAudio(speakerOn: Boolean) {
        val am = audioManager ?: return
        prevAudioMode = am.mode
        @Suppress("DEPRECATION")
        prevSpeakerOn = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }

        applyAudioRoute(speakerOn)
    }

    private fun applyAudioRoute(speakerOn: Boolean) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val target = if (speakerOn) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) {
                am.setCommunicationDevice(target)
                return
            }
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerOn
    }

    private fun restoreAudio() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am.clearCommunicationDevice()
            } catch (_: Throwable) {}
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = prevSpeakerOn
        am.mode = prevAudioMode
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun releaseAudioEffects() {
        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        aec = null
        ns = null
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            val sampleRate = 16000
            val frameSamples = 512 // 32ms per frame
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameSamples * 8)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            } catch (se: SecurityException) {
                _uiState.value = _uiState.value.copy(statusNote = "Mic permission denied")
                stopCall()
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                _uiState.value = _uiState.value.copy(statusNote = "Mic init failed")
                recorder.release()
                stopCall()
                return@launch
            }

            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }

            val preRollMaxFrames = 8 // ~256ms
            val preRoll = ArrayDeque<ShortArray>(preRollMaxFrames + 1)
            val speechFrames = mutableListOf<ShortArray>()
            val frameBuf = ShortArray(frameSamples)

            val bandAnalyzer = SpeechBandAnalyzer()
            val silero = runCatching { SileroVadDetector(this@VoiceCallService) }.getOrNull()

            var speaking = false
            var speechStartMs = 0L
            var lastVoiceMs = 0L
            var bargeInStreak = 0

            try {
                recorder.startRecording()
                while (_uiState.value.inCall) {
                    val read = recorder.read(frameBuf, 0, frameSamples)
                    if (read <= 0) continue

                    val state = _uiState.value
                    if (state.isMuted) {
                        speaking = false
                        bargeInStreak = 0
                        speechFrames.clear()
                        preRoll.clear()
                        silero?.reset()
                        continue
                    }

                    val settings = CallSettings.load(this@VoiceCallService)
                    val isTtsActive = ttsSpeaking.get() || (System.currentTimeMillis() - lastTtsEndedAtMs < 400L)

                    val stats = bandAnalyzer.analyze(frameBuf, read)
                    val speechProb = if (silero != null && read == frameSamples) {
                        silero.processFrame(frameBuf)
                    } else {
                        1f
                    }
                    val frameCopy = frameBuf.copyOf(read)

                    if (isTtsActive) {
                        if (!settings.bargeIn) {
                            bargeInStreak = 0
                            continue
                        }
                        val bargeThreshold = maxOf(
                            settings.vadThreshold * 1.6f,
                            bandAnalyzer.noiseFloorRms * 3.5f,
                            0.025f
                        )
                        val isHumanBarge = stats.isSpeechBand &&
                            stats.bandRms >= bargeThreshold &&
                            speechProb >= 0.55f

                        if (isHumanBarge) {
                            bargeInStreak++
                            preRoll.addLast(frameCopy)
                            if (preRoll.size > preRollMaxFrames) preRoll.removeFirst()
                            if (bargeInStreak >= 5) { // ~160ms sustained human speech
                                bargeInStreak = 0
                                interruptSpeaking()
                                speaking = true
                                speechStartMs = System.currentTimeMillis()
                                lastVoiceMs = speechStartMs
                                speechFrames.clear()
                                speechFrames.addAll(preRoll)
                                preRoll.clear()
                                _uiState.value = _uiState.value.copy(
                                    phase = CallPhase.RECORDING,
                                    statusNote = "Listening to you..."
                                )
                            }
                        } else {
                            bargeInStreak = 0
                        }
                        continue
                    }

                    val listenThreshold = maxOf(settings.vadThreshold, bandAnalyzer.noiseFloorRms * 2.4f)
                    val isSpeech = stats.isSpeechBand &&
                        stats.bandRms >= listenThreshold &&
                        speechProb >= 0.45f

                    val now = System.currentTimeMillis()
                    if (isSpeech) {
                        lastVoiceMs = now
                        if (!speaking) {
                            speaking = true
                            speechStartMs = now
                            speechFrames.clear()
                            speechFrames.addAll(preRoll)
                            preRoll.clear()
                            _uiState.value = _uiState.value.copy(
                                phase = CallPhase.RECORDING,
                                statusNote = "Hearing speech..."
                            )
                        }
                        speechFrames.add(frameCopy)
                    } else if (speaking) {
                        speechFrames.add(frameCopy)
                        if (now - lastVoiceMs >= settings.vadSilenceMs) {
                            speaking = false
                            val durMs = lastVoiceMs - speechStartMs
                            if (durMs >= settings.minSpeechMs && speechFrames.isNotEmpty()) {
                                val totalSamples = speechFrames.sumOf { it.size }
                                val pcm = ShortArray(totalSamples)
                                var offset = 0
                                for (f in speechFrames) {
                                    f.copyInto(pcm, offset)
                                    offset += f.size
                                }
                                speechFrames.clear()
                                launchTurn(pcm)
                            } else {
                                speechFrames.clear()
                                _uiState.value = _uiState.value.copy(
                                    phase = CallPhase.LISTENING,
                                    statusNote = "Listening..."
                                )
                            }
                        }
                    } else {
                        bandAnalyzer.updateNoiseFloor(stats.bandRms)
                        preRoll.addLast(frameCopy)
                        if (preRoll.size > preRollMaxFrames) preRoll.removeFirst()
                    }
                }
            } finally {
                silero?.close()
                try { recorder.stop() } catch (_: Throwable) {}
                recorder.release()
                releaseAudioEffects()
            }
        }
    }

    fun submitTextTurn(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        val myTurnId = activeTurnId.incrementAndGet()
        ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        stopActiveTrack()
        activeChatCall?.cancel()
        activeChatCall = null
        turnJob?.cancel()
        turnJob = serviceScope.launch {
            try {
                executeChatTurn(text)
            } catch (_: CancellationException) {
                // Interrupted
            } catch (t: Throwable) {
                if (isExpectedCancellation(t, myTurnId)) return@launch
                handleTurnError(t)
            } finally {
                if (activeTurnId.get() == myTurnId) {
                    activeChatCall = null
                }
            }
        }
    }

    private fun launchTurn(pcm: ShortArray) {
        val myTurnId = activeTurnId.incrementAndGet()
        activeChatCall?.cancel()
        activeChatCall = null
        turnJob?.cancel()
        turnJob = serviceScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    phase = CallPhase.TRANSCRIBING,
                    statusNote = "Transcribing..."
                )
                updateNotification()

                val wav = BridgeApi.pcm16ToWav(pcm, 16000)
                val transcript = api.transcribeWav(wav)
                if (transcript.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        phase = if (_uiState.value.isMuted) CallPhase.MUTED else CallPhase.LISTENING,
                        statusNote = "Listening..."
                    )
                    updateNotification()
                    return@launch
                }

                executeChatTurn(transcript)
            } catch (_: CancellationException) {
                // Interrupted by barge-in or hangup
            } catch (t: Throwable) {
                if (isExpectedCancellation(t, myTurnId)) return@launch
                handleTurnError(t)
            } finally {
                if (activeTurnId.get() == myTurnId) {
                    activeChatCall = null
                }
            }
        }
    }

    private fun isExpectedCancellation(t: Throwable, myTurnId: Long): Boolean {
        if (activeTurnId.get() != myTurnId) return true
        if (!_uiState.value.inCall) return true
        if (activeChatCall?.isCanceled() == true) return true
        val msg = t.message.orEmpty()
        return msg.contains("Canceled", ignoreCase = true) ||
            msg.contains("Socket closed", ignoreCase = true) && activeChatCall?.isCanceled() == true
    }

    private fun handleTurnError(t: Throwable) {
        val list = _uiState.value.messages.toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.role == "assistant" && last.text == "...") {
            list[list.lastIndex] = ChatMessage("assistant", "Error: ${t.message ?: "Network error"}")
        }
        _uiState.value = _uiState.value.copy(
            phase = if (_uiState.value.isMuted) CallPhase.MUTED else CallPhase.LISTENING,
            messages = list,
            statusNote = "Error: ${t.message ?: "retry listening"}"
        )
        updateNotification()
    }

    private suspend fun executeChatTurn(transcript: String) {
        val updatedMsgs = _uiState.value.messages.toMutableList()
        updatedMsgs.add(ChatMessage("user", transcript))
        val assistantIdx = updatedMsgs.size
        updatedMsgs.add(ChatMessage("assistant", "..."))

        _uiState.value = _uiState.value.copy(
            phase = CallPhase.THINKING,
            messages = updatedMsgs,
            statusNote = "Thinking..."
        )
        updateNotification()

        val gen = ttsGeneration.incrementAndGet()
        ttsQueue.clear()
        var spokenCursor = 0

        api.streamChat(
                    prompt = transcript,
                    sessionId = _uiState.value.sessionId,
                    onCallCreated = { activeChatCall = it },
                    onSession = { sid ->
                        val s = CallSettings.load(this@VoiceCallService).copy(lastSessionId = sid)
                        CallSettings.save(this@VoiceCallService, s)
                        _uiState.value = _uiState.value.copy(sessionId = sid)
                    },
                    onStatus = { status ->
                        if (_uiState.value.phase == CallPhase.THINKING) {
                            _uiState.value = _uiState.value.copy(statusNote = status)
                            updateNotification()
                        }
                    },
                    onText = { fullText ->
                        val list = _uiState.value.messages.toMutableList()
                        if (assistantIdx < list.size) {
                            list[assistantIdx] = ChatMessage("assistant", fullText)
                            _uiState.value = _uiState.value.copy(messages = list)
                        }
                        val (sentences, nextCursor) = SpeechTextUtils.extractCompletedSentences(fullText, spokenCursor)
                        spokenCursor = nextCursor
                        for (s in sentences) {
                            enqueueSentence(gen, s)
                        }
                    },
                    onDone = { sid, fullText ->
                        val finalSid = sid ?: _uiState.value.sessionId
                        if (!finalSid.isNullOrBlank()) {
                            val s = CallSettings.load(this@VoiceCallService).copy(lastSessionId = finalSid)
                            CallSettings.save(this@VoiceCallService, s)
                        }
                        val list = _uiState.value.messages.toMutableList()
                        if (assistantIdx < list.size && fullText.isNotBlank()) {
                            list[assistantIdx] = ChatMessage("assistant", fullText)
                        }
                        _uiState.value = _uiState.value.copy(
                            sessionId = finalSid,
                            messages = list
                        )
                        val body = if (fullText.isNotBlank()) fullText else list.getOrNull(assistantIdx)?.text.orEmpty()
                        val rem = if (spokenCursor < body.length) body.substring(spokenCursor).trim() else ""
                        if (rem.isNotBlank() && rem != "...") {
                            enqueueSentence(gen, rem)
                        }
                    }
                )
    }

    private fun enqueueSentence(gen: Int, rawSentence: String) {
        val clean = SpeechTextUtils.stripMarkdownForSpeech(rawSentence)
        if (clean.isBlank()) return
        ttsQueue.offer(gen to clean)
    }

    private fun startTtsWorker() {
        ttsThread?.interrupt()
        ttsThread = Thread {
            while (!Thread.currentThread().isInterrupted && _uiState.value.inCall) {
                try {
                    val (gen, text) = ttsQueue.take()
                    if (gen != ttsGeneration.get()) continue

                    ttsSpeaking.set(true)
                    _uiState.value = _uiState.value.copy(
                        phase = CallPhase.SPEAKING,
                        statusNote = "Speaking..."
                    )
                    updateNotification()

                    val wavBytes = api.synthesizeTts(text)
                    if (gen != ttsGeneration.get() || !_uiState.value.inCall) continue
                    playWavBlocking(wavBytes, gen)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    // Skip failed TTS chunk without crashing call
                } finally {
                    if (ttsQueue.isEmpty()) {
                        ttsSpeaking.set(false)
                        lastTtsEndedAtMs = System.currentTimeMillis()
                        if (_uiState.value.inCall) {
                            _uiState.value = _uiState.value.copy(
                                phase = if (_uiState.value.isMuted) CallPhase.MUTED else CallPhase.LISTENING,
                                statusNote = if (_uiState.value.isMuted) "Muted" else "Listening..."
                            )
                            updateNotification()
                        }
                    }
                }
            }
        }.apply {
            name = "ClaudianTtsPlayer"
            start()
        }
    }

    private fun playWavBlocking(wav: ByteArray, gen: Int) {
        if (wav.size < 44) return
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = bb.getInt(24).takeIf { it in 8000..48000 } ?: 24000

        // Locate "data" chunk cleanly
        var offset = 12
        var dataOffset = 44
        var dataSize = wav.size - 44
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = bb.getInt(offset + 4)
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = size.coerceAtMost(wav.size - dataOffset)
                break
            }
            offset += 8 + size + (size and 1)
        }
        if (dataSize <= 0) return

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        try {
            track.play()
            var pos = dataOffset
            val end = dataOffset + dataSize
            val chunkSize = 4096
            while (pos < end && gen == ttsGeneration.get() && _uiState.value.inCall) {
                val n = minOf(chunkSize, end - pos)
                val written = track.write(wav, pos, n)
                if (written <= 0) break
                pos += written
            }
        } finally {
            activeTrack = null
            try {
                if (gen != ttsGeneration.get() || !_uiState.value.inCall) {
                    track.pause()
                    track.flush()
                }
                track.stop()
            } catch (_: Throwable) {}
            track.release()
        }
    }

    private fun stopActiveTrack() {
        val t = activeTrack ?: return
        activeTrack = null
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Claudian Voice Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps voice call active when screen is off"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (!_uiState.value.inCall) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(_uiState.value))
    }

    private fun buildNotification(state: CallUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceCallService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val speakerIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceCallService::class.java).setAction(ACTION_TOGGLE_SPEAKER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hangupIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Claudian Call Active")
            .setContentText(state.statusNote)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, if (state.isMuted) "Unmute" else "Mute", muteIntent)
            .addAction(0, if (state.speakerOn) "Earpiece" else "Speaker", speakerIntent)
            .addAction(0, "Hang Up", hangupIntent)
            .build()
    }

    override fun onDestroy() {
        stopCall()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "claudian_call_channel"
        const val NOTIF_ID = 8791
        const val ACTION_START_CALL = "com.claudian.call.START"
        const val ACTION_HANGUP = "com.claudian.call.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.claudian.call.MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.claudian.call.SPEAKER"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
