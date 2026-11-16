package com.claudian.call

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var api: BridgeApi
    private var service: VoiceCallService? = null
    private var bound = false
    private var stateJob: Job? = null
    private var timerJob: Job? = null

    private var sessions: List<BridgeSession> = emptyList()
    private var selectedSessionId: String? = null
    private var localMessages: List<ChatMessage> = emptyList()

    private lateinit var statusDot: TextView
    private lateinit var sessionBtn: Button
    private lateinit var phaseText: TextView
    private lateinit var providerBadge: TextView
    private lateinit var interruptBtn: Button
    private lateinit var timerText: TextView
    private lateinit var messagesScroll: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var muteBtn: Button
    private lateinit var callBtn: Button
    private lateinit var speakerBtn: Button

    private var qrPendingCallback: ((String, String) -> Unit)? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim() ?: return@registerForActivityResult
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
        if (parsed?.scheme == "claudian" && parsed.host == "pair") {
            val url = parsed.getQueryParameter("url")?.trim()?.trimEnd('/') ?: ""
            val token = parsed.getQueryParameter("token")?.trim() ?: ""
            if (url.isNotBlank()) {
                val cb = qrPendingCallback
                qrPendingCallback = null
                if (cb != null) {
                    cb(url, token)
                } else {
                    CallSettings.save(this, CallSettings.load(this).copy(bridgeUrl = url, authToken = token))
                    Toast.makeText(this, "Paired with $url", Toast.LENGTH_SHORT).show()
                    updateProviderBadge()
                    refreshSessionsAndHealth()
                }
                return@registerForActivityResult
            }
        }
        qrPendingCallback = null
        Toast.makeText(this, "Unrecognized QR code (expected claudian://pair)", Toast.LENGTH_LONG).show()
    }

    private fun launchQrScanner(onScanned: ((String, String) -> Unit)? = null) {
        qrPendingCallback = onScanned
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR code printed by ./setup.sh")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrLauncher.launch(opts)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            startVoiceCallService()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice calls", Toast.LENGTH_LONG).show()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as VoiceCallService.LocalBinder).getService()
            service = svc
            bound = true
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                svc.uiState.collectLatest { st ->
                    renderCallState(st)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BridgeApi { CallSettings.load(this) }
        handlePairingIntent(intent)
        selectedSessionId = CallSettings.load(this).lastSessionId.ifBlank { null }
        buildUi()
        bindService(Intent(this, VoiceCallService::class.java), conn, Context.BIND_AUTO_CREATE)
        refreshSessionsAndHealth()
        if (CallSettings.load(this).authToken.isBlank()) {
            showFirstRunOnboarding()
        }
        if (BuildConfig.ENABLE_SELF_UPDATE) {
            checkForAppUpdate(manual = false)
        }
    }

    private fun showFirstRunOnboarding() {
        AlertDialog.Builder(this)
            .setTitle("Connect to Claudian Bridge")
            .setMessage("Run ./setup.sh on your workstation, then scan its QR code or auto-discover on your LAN.")
            .setPositiveButton("Scan QR Code") { _, _ -> launchQrScanner() }
            .setNeutralButton("Auto-Discover LAN") { _, _ ->
                Toast.makeText(this, "Searching LAN for _claudian._tcp...", Toast.LENGTH_SHORT).show()
                discoverBridgeOnLan { foundUrl ->
                    runOnUiThread {
                        if (foundUrl != null) {
                            CallSettings.save(this, CallSettings.load(this).copy(bridgeUrl = foundUrl))
                            Toast.makeText(this, "Found $foundUrl — paste your Auth Token", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "No bridge found on LAN", Toast.LENGTH_SHORT).show()
                        }
                        showSettingsDialog()
                    }
                }
            }
            .setNegativeButton("Manual Setup") { _, _ -> showSettingsDialog() }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handlePairingIntent(intent)) {
            updateProviderBadge()
            refreshSessionsAndHealth()
        }
    }

    private fun handlePairingIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "claudian" || data.host != "pair") return false
        val url = data.getQueryParameter("url")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return false
        val token = data.getQueryParameter("token")?.trim().orEmpty()
        if (!BridgeSecurity.isAllowedBridgeUrl(url)) {
            Toast.makeText(this, "Rejected unsafe bridge URL: $url", Toast.LENGTH_LONG).show()
            return false
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with this computer?")
            .setMessage("Save bridge URL $url and update your pairing token?")
            .setPositiveButton("Pair") { _, _ ->
                val updated = CallSettings.load(this).copy(bridgeUrl = url, authToken = token)
                CallSettings.save(this, updated)
                Toast.makeText(this, "Paired with $url", Toast.LENGTH_SHORT).show()
                updateProviderBadge()
                refreshSessionsAndHealth()
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    override fun onDestroy() {
        timerJob?.cancel()
        stateJob?.cancel()
        if (bound) {
            unbindService(conn)
            bound = false
        }
        super.onDestroy()
    }

    private fun pillBg(fillHex: String, strokeHex: String = "#2A2E3B", radiusDp: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1014"))
            setPadding(dp(14), dp(12), dp(14), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(14), bars.top + dp(10), dp(14), bars.bottom + dp(12))
            insets
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusDot = TextView(this).apply {
            text = "●"
            textSize = 13f
            setTextColor(Color.parseColor("#888B94"))
            setPadding(dp(2), 0, dp(8), 0)
        }
        header.addView(statusDot)

        sessionBtn = Button(this).apply {
            text = "New conversation"
            isAllCaps = false
            textSize = 13f
            maxLines = 1
            setTextColor(Color.WHITE)
            background = pillBg("#1B1D24")
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showSessionPicker()
            }
        }
        header.addView(sessionBtn)

        val newSessionBtn = Button(this).apply {
            text = "＋"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = pillBg("#1B1D24")
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startNewSession()
                Toast.makeText(this@MainActivity, "Started new conversation", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(newSessionBtn)

        val syncBtn = Button(this).apply {
            text = "↻"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = pillBg("#1B1D24")
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                refreshSessionsAndHealth()
            }
        }
        header.addView(syncBtn)

        val settingsBtn = Button(this).apply {
            text = "⚙"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = pillBg("#1B1D24")
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showSettingsDialog()
            }
        }
        header.addView(settingsBtn)
        root.addView(header)

        // Call status + provider + timer card
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#161820", "#252936", 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }

        val statusLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        phaseText = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E5E7EB"))
        }
        providerBadge = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, dp(2), dp(8), dp(2))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showWorkspacePicker()
            }
        }
        statusLeft.addView(phaseText)
        statusLeft.addView(providerBadge)

        interruptBtn = Button(this).apply {
            text = "Interrupt"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            background = pillBg("#7C5CFF", "#9B82FF", 12)
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply { marginEnd = dp(10) }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                service?.interruptSpeaking()
            }
        }

        timerText = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#9CA3AF"))
        }
        statusBar.addView(statusLeft)
        statusBar.addView(interruptBtn)
        statusBar.addView(timerText)
        root.addView(statusBar)

        // Quick action chip bar (Recap & Save)
        val wfScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val wfRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val recapChip = Button(this).apply {
            text = "↻ Recap"
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.parseColor("#E5E7EB"))
            background = pillBg("#1E2433", "#334155", 16)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                triggerSessionRecap()
            }
        }
        wfRow.addView(recapChip)

        val saveChip = Button(this).apply {
            text = "Save"
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.parseColor("#E5E7EB"))
            background = pillBg("#1E2433", "#334155", 16)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
            )
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val activeMsgs = service?.uiState?.value?.messages?.takeIf { m -> m.isNotEmpty() } ?: localMessages
                if (activeMsgs.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Nothing to save yet", Toast.LENGTH_SHORT).show()
                } else {
                    val body = activeMsgs.joinToString("\n\n") { m ->
                        val speaker = if (m.role == "user") "**User**" else "**Assistant**"
                        "$speaker: ${m.text}"
                    }
                    val defaultTitle = sessions.firstOrNull { s -> s.sessionId == selectedSessionId }?.title ?: "Voice Session"
                    saveToObsidianVault(defaultTitle, body)
                }
            }
        }
        wfRow.addView(saveChip)
        wfScroll.addView(wfRow)
        root.addView(wfScroll)

        // Transcript area
        messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        messagesScroll.addView(messagesContainer)
        root.addView(messagesScroll)

        // Hybrid text/paste input bar for exact symbols or errors mid-call
        val textBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#161820", "#252936", 20)
            setPadding(dp(12), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val textInput = EditText(this).apply {
            hint = "Type or paste code/error..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            background = null
            textSize = 13f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendTextBtn = Button(this).apply {
            text = "Send"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = pillBg("#7C5CFF", "#9D85FF", 16)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
            setOnClickListener {
                val msg = textInput.text.toString().trim()
                if (msg.isNotEmpty()) {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    textInput.setText("")
                    val svc = service
                    if (svc != null && svc.uiState.value.inCall) {
                        svc.submitTextTurn(msg)
                    } else {
                        Toast.makeText(this@MainActivity, "Tap Call first to talk or send", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        textBar.addView(textInput)
        textBar.addView(sendTextBtn)
        root.addView(textBar)

        // Bottom call control dock
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pillBg("#161820", "#252936", 26)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        muteBtn = makeRoundControl("Mute", "#232630") {
            service?.toggleMute()
        }
        callBtn = makeRoundControl("Call", "#22C55E") {
            val inCall = service?.uiState?.value?.inCall == true
            if (inCall) {
                service?.stopCall()
            } else {
                requestPermissionsAndStart()
            }
        }
        speakerBtn = makeRoundControl("Speaker", "#232630") {
            if (service?.uiState?.value?.inCall == true) {
                service?.toggleSpeaker()
            } else {
                val s = CallSettings.load(this)
                CallSettings.save(this, s.copy(speakerOn = !s.speakerOn))
                updateSpeakerStyle(!s.speakerOn)
            }
        }

        controls.addView(muteBtn, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(20) })
        controls.addView(callBtn, LinearLayout.LayoutParams(dp(82), dp(82)).apply { marginEnd = dp(20) })
        controls.addView(speakerBtn, LinearLayout.LayoutParams(dp(72), dp(72)))

        root.addView(controls)
        setContentView(root)
        updateProviderBadge()
        updateSpeakerStyle(CallSettings.load(this).speakerOn)
    }

    private fun updateProviderBadge() {
        val s = CallSettings.load(this)
        val modelLabel = s.model.ifBlank { "default" }
        val wsLabel = s.workspaceDir.trimEnd('/').substringAfterLast('/').ifBlank { "default" }
        providerBadge.text = "${s.provider.uppercase()} · $modelLabel · 📁 $wsLabel ▾"
    }

    private fun startNewSession() {
        selectedSessionId = null
        localMessages = emptyList()
        val s = CallSettings.load(this).copy(lastSessionId = "")
        CallSettings.save(this, s)
        service?.resetSession()
        updateSessionButtonLabel()
        renderMessages(emptyList())
    }

    private fun showWorkspacePicker() {
        lifecycleScope.launch {
            val workspaces = withContext(Dispatchers.IO) {
                runCatching { api.listWorkspaces() }.getOrDefault(emptyList())
            }
            val labels = mutableListOf("＋ Create new folder...")
            labels.addAll(workspaces.map { "📁 ${it.name}" })
            val currentWs = CallSettings.load(this@MainActivity).workspaceDir
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Project / Folder")
                .setItems(labels.toTypedArray()) { _, idx ->
                    if (idx == 0) {
                        promptCreateWorkspace()
                    } else {
                        val chosen = workspaces[idx - 1]
                        if (chosen.path != currentWs) {
                            val updated = CallSettings.load(this@MainActivity).copy(
                                workspaceDir = chosen.path,
                                lastSessionId = ""
                            )
                            CallSettings.save(this@MainActivity, updated)
                            updateProviderBadge()
                            startNewSession()
                            refreshSessionsAndHealth()
                        }
                    }
                }
                .show()
        }
    }

    private fun promptCreateWorkspace() {
        val input = EditText(this).apply {
            hint = "Folder name (e.g. voice-notes-app)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New Project Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val created = withContext(Dispatchers.IO) {
                        runCatching { api.createWorkspace(name) }
                    }
                    created.onSuccess { ws ->
                        val updated = CallSettings.load(this@MainActivity).copy(
                            workspaceDir = ws.path,
                            lastSessionId = ""
                        )
                        CallSettings.save(this@MainActivity, updated)
                        updateProviderBadge()
                        startNewSession()
                        refreshSessionsAndHealth()
                        Toast.makeText(this@MainActivity, "Switched to ${ws.name}", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(this@MainActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerSessionRecap() {
        val sid = service?.uiState?.value?.sessionId ?: selectedSessionId
        if (sid.isNullOrBlank()) {
            Toast.makeText(this, "Select a previous conversation first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Generating 5s recap...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { api.recapSession(sid) }
            }
            res.onSuccess { recap ->
                val svc = service
                if (svc != null && svc.uiState.value.inCall) {
                    svc.speakRecap(recap)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Session Recap")
                        .setMessage(recap)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Recap failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToObsidianVault(title: String, content: String) {
        val ws = CallSettings.load(this).workspaceDir
        val sid = service?.uiState?.value?.sessionId ?: selectedSessionId
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { api.saveToVault(title, content, ws, sid) }
            }
            res.onSuccess { rel ->
                Toast.makeText(this@MainActivity, "Saved to $rel", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Save failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makeRoundControl(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hexColor))
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
    }

    private fun setOvalColor(btn: Button, hex: String) {
        (btn.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
    }

    private fun updateSpeakerStyle(speakerOn: Boolean) {
        speakerBtn.text = if (speakerOn) "Spkr ON" else "Earpiece"
        setOvalColor(speakerBtn, if (speakerOn) "#7C5CFF" else "#232630")
    }

    private fun renderCallState(st: CallUiState) {
        phaseText.text = st.statusNote
        val phaseColor = when (st.phase) {
            CallPhase.RECORDING -> "#22C55E"
            CallPhase.SPEAKING -> "#7C5CFF"
            CallPhase.THINKING, CallPhase.TRANSCRIBING -> "#F59E0B"
            CallPhase.MUTED -> "#EF4444"
            else -> "#C7C9D1"
        }
        phaseText.setTextColor(Color.parseColor(phaseColor))

        if (st.inCall) {
            callBtn.text = "End"
            setOvalColor(callBtn, "#EF4444")
            muteBtn.isEnabled = true
            muteBtn.alpha = 1f
            muteBtn.text = if (st.isMuted) "Unmute" else "Mute"
            setOvalColor(muteBtn, if (st.isMuted) "#EF4444" else "#232630")
            interruptBtn.visibility = if (st.phase == CallPhase.SPEAKING) View.VISIBLE else View.GONE
            updateSpeakerStyle(st.speakerOn)

            if (timerJob == null || timerJob?.isActive != true) {
                timerJob = lifecycleScope.launch {
                    while (true) {
                        val elapsedSec = ((System.currentTimeMillis() - st.callStartedAtMs) / 1000L).coerceAtLeast(0L)
                        timerText.text = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                        delay(1000L)
                    }
                }
            }
        } else {
            callBtn.text = "Call"
            setOvalColor(callBtn, "#22C55E")
            muteBtn.isEnabled = false
            muteBtn.alpha = 0.4f
            muteBtn.text = "Mute"
            setOvalColor(muteBtn, "#1A1C23")
            interruptBtn.visibility = View.GONE
            timerJob?.cancel()
            timerJob = null
            timerText.text = "00:00"
        }

        if (st.sessionId != null && st.sessionId != selectedSessionId) {
            selectedSessionId = st.sessionId
            updateSessionButtonLabel()
        }

        val msgs = if (st.messages.isNotEmpty()) st.messages else localMessages
        renderMessages(msgs)
    }

    private fun renderMessages(msgs: List<ChatMessage>) {
        messagesContainer.removeAllViews()
        if (msgs.isEmpty()) {
            messagesContainer.addView(TextView(this).apply {
                text = "Tap Call to talk hands-free, even with the screen off."
                textSize = 14f
                setTextColor(Color.parseColor("#888B94"))
                setPadding(dp(8), dp(24), dp(8), dp(8))
                gravity = Gravity.CENTER
            })
            return
        }

        for (m in msgs) {
            val isUser = m.role == "user"
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isUser) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val bubble = TextView(this).apply {
                text = m.text
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(if (isUser) "#6D4AFF" else "#1B1D24"))
                }
                maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    saveToObsidianVault(m.text.take(48), m.text)
                    true
                }
            }
            wrap.addView(bubble)
            messagesContainer.addView(wrap)
        }
        messagesScroll.post { messagesScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun refreshSessionsAndHealth() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.health() }.getOrDefault(false)
            }
            statusDot.setTextColor(Color.parseColor(if (ok) "#22C55E" else "#EF4444"))
            if (!ok) {
                phaseText.text = "Bridge offline"
                return@launch
            }
            val currentSettings = CallSettings.load(this@MainActivity)
            val list = withContext(Dispatchers.IO) {
                runCatching { api.listSessions(currentSettings.workspaceDir) }.getOrDefault(emptyList())
            }
            sessions = list
            val sid = selectedSessionId ?: currentSettings.lastSessionId.ifBlank { null }
            selectedSessionId = sid
            updateSessionButtonLabel()
            if (!sid.isNullOrBlank()) {
                val msgs = withContext(Dispatchers.IO) {
                    runCatching { api.getSessionMessages(sid) }.getOrDefault(emptyList())
                }
                localMessages = msgs
                if (service?.uiState?.value?.inCall != true) {
                    service?.setSessionContext(sid, msgs)
                    renderMessages(msgs)
                }
            } else if (service?.uiState?.value?.inCall != true) {
                localMessages = emptyList()
                service?.setSessionContext(null, emptyList())
                renderMessages(emptyList())
            }
        }
    }

    private fun updateSessionButtonLabel() {
        val found = sessions.firstOrNull { it.sessionId == selectedSessionId }
        val base = found?.title?.take(24) ?: if (selectedSessionId != null) "Active call" else "New conversation"
        val countSuffix = if (sessions.isNotEmpty() && selectedSessionId == null) " (${sessions.size})" else ""
        sessionBtn.text = "$base$countSuffix ▾"
    }

    private fun showSessionPicker() {
        val labels = mutableListOf("＋ New conversation")
        labels.addAll(sessions.map { "${it.title} (${it.selectedModel})" })
        AlertDialog.Builder(this)
            .setTitle("Conversation")
            .setItems(labels.toTypedArray()) { _, idx ->
                if (idx == 0) {
                    startNewSession()
                } else {
                    val chosen = sessions[idx - 1]
                    selectedSessionId = chosen.sessionId
                    updateSessionButtonLabel()
                    chosen.sessionId?.let { sid ->
                        val s = CallSettings.load(this).copy(lastSessionId = sid)
                        CallSettings.save(this, s)
                        lifecycleScope.launch {
                            val msgs = withContext(Dispatchers.IO) {
                                runCatching { api.getSessionMessages(sid) }.getOrDefault(emptyList())
                            }
                            localMessages = msgs
                            service?.setSessionContext(sid, msgs)
                            renderMessages(msgs)
                        }
                    }
                }
            }
            .show()
    }

    private fun showSettingsDialog() {
        val s = CallSettings.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        fun addField(label: String, value: String, password: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#A1A5B2"))
            })
            return EditText(this).apply {
                setText(value)
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                box.addView(this)
            }
        }

        val urlInput = addField("Bridge URL (or paste claudian://pair link)", s.bridgeUrl)
        val tokenInput = addField("Auth Token", s.authToken, password = true)
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val scanQrBtn = Button(this).apply {
            text = "Scan QR Code"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                launchQrScanner { scannedUrl, scannedToken ->
                    urlInput.setText(scannedUrl)
                    if (scannedToken.isNotBlank()) tokenInput.setText(scannedToken)
                    Toast.makeText(this@MainActivity, "Scanned $scannedUrl", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val discoverBtn = Button(this).apply {
            text = "Discover LAN"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                text = "Searching..."
                isEnabled = false
                discoverBridgeOnLan { foundUrl ->
                    runOnUiThread {
                        isEnabled = true
                        text = "Discover LAN"
                        if (foundUrl != null) {
                            urlInput.setText(foundUrl)
                            Toast.makeText(this@MainActivity, "Found $foundUrl", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No bridge found on LAN", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        pairRow.addView(scanQrBtn)
        pairRow.addView(discoverBtn)
        box.addView(pairRow)

        box.addView(TextView(this).apply {
            text = "Provider"
            textSize = 12f
            setTextColor(Color.parseColor("#A1A5B2"))
        })
        val providers = arrayOf("claude", "agy", "opencode")
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providers)
            setSelection(providers.indexOf(s.provider).coerceAtLeast(0))
        }
        box.addView(providerSpinner)

        box.addView(TextView(this).apply {
            text = "Model"
            textSize = 12f
            setTextColor(Color.parseColor("#A1A5B2"))
        })
        var currentModels = mutableListOf(
            if (s.model.isNotBlank()) ModelOption(s.model, s.model) else ModelOption("", "Default (auto)")
        )
        val modelSpinner = Spinner(this)
        fun applyModelOptions(opts: List<ModelOption>, preferredId: String) {
            currentModels = opts.toMutableList()
            if (currentModels.isEmpty()) currentModels.add(ModelOption("", "Default (auto)"))
            val labels = currentModels.map { it.label.ifBlank { it.id.ifBlank { "Default (auto)" } } }
            modelSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = currentModels.indexOfFirst { it.id == preferredId }
            modelSpinner.setSelection(if (idx >= 0) idx else 0)
        }
        applyModelOptions(currentModels, s.model)
        box.addView(modelSpinner)

        fun loadModelsFor(provider: String, preferredId: String) {
            lifecycleScope.launch {
                val fetched = withContext(Dispatchers.IO) {
                    runCatching { api.listModels(provider) }.getOrNull()
                }
                if (!fetched.isNullOrEmpty()) {
                    applyModelOptions(fetched, preferredId)
                }
            }
        }

        var firstProviderBind = true
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val chosenProvider = providers[pos]
                val pref = if (firstProviderBind) s.model else ""
                firstProviderBind = false
                loadModelsFor(chosenProvider, pref)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        box.addView(TextView(this).apply {
            text = "Workspace"
            textSize = 12f
            setTextColor(Color.parseColor("#A1A5B2"))
        })
        var currentWorkspaces = mutableListOf(
            if (s.workspaceDir.isNotBlank()) WorkspaceOption(s.workspaceDir, s.workspaceDir)
            else WorkspaceOption("", "Bridge Default")
        )
        val workspaceSpinner = Spinner(this)
        fun applyWorkspaceOptions(opts: List<WorkspaceOption>, preferredPath: String) {
            currentWorkspaces = mutableListOf(WorkspaceOption("", "Bridge Default"))
            currentWorkspaces.addAll(opts)
            val labels = currentWorkspaces.map { it.name.ifBlank { it.path } }
            workspaceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = currentWorkspaces.indexOfFirst { it.path == preferredPath }
            workspaceSpinner.setSelection(if (idx >= 0) idx else 0)
        }
        applyWorkspaceOptions(emptyList(), s.workspaceDir)
        box.addView(workspaceSpinner)
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { api.listWorkspaces() }.getOrNull()
            }
            if (!fetched.isNullOrEmpty()) {
                applyWorkspaceOptions(fetched, s.workspaceDir)
            }
        }

        val voiceInput = addField("Kokoro Voice", s.kokoroVoice)
        val rateInput = addField("Speech Rate", s.speechRate.toString())
        val silenceInput = addField("VAD Silence (ms)", s.vadSilenceMs.toString())
        val bargeCheck = CheckBox(this).apply {
            text = "Allow voice interruption (barge-in)"
            isChecked = s.bargeIn
        }
        box.addView(bargeCheck)

        if (BuildConfig.ENABLE_SELF_UPDATE) {
            val updateBtn = Button(this).apply {
                text = "Check for App Update"
                isAllCaps = false
                setOnClickListener { checkForAppUpdate(manual = true) }
            }
            box.addView(updateBtn)
        }

        AlertDialog.Builder(this)
            .setTitle("Claudian Call Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                var rawUrl = urlInput.text.toString().trim().trimEnd('/')
                var rawToken = tokenInput.text.toString().trim()
                if (rawUrl.startsWith("claudian://pair")) {
                    val parsed = Uri.parse(rawUrl)
                    parsed.getQueryParameter("url")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let {
                        rawUrl = it
                    }
                    parsed.getQueryParameter("token")?.trim()?.takeIf { it.isNotBlank() }?.let {
                        rawToken = it
                    }
                }
                val updated = s.copy(
                    bridgeUrl = rawUrl,
                    authToken = rawToken,
                    provider = providers[providerSpinner.selectedItemPosition],
                    model = currentModels.getOrNull(modelSpinner.selectedItemPosition)?.id ?: "",
                    workspaceDir = currentWorkspaces.getOrNull(workspaceSpinner.selectedItemPosition)?.path ?: "",
                    kokoroVoice = voiceInput.text.toString().trim().ifBlank { "af_heart" },
                    speechRate = rateInput.text.toString().toFloatOrNull() ?: 1.05f,
                    vadSilenceMs = silenceInput.text.toString().toLongOrNull()?.coerceIn(500L, 5000L) ?: 1800L,
                    bargeIn = bargeCheck.isChecked
                )
                CallSettings.save(this, updated)
                updateProviderBadge()
                refreshSessionsAndHealth()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        } else {
            startVoiceCallService()
        }
    }

    private fun startVoiceCallService() {
        service?.setSessionContext(selectedSessionId, localMessages)
        val intent = Intent(this, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_START_CALL
            putExtra(VoiceCallService.EXTRA_SESSION_ID, selectedSessionId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun discoverBridgeOnLan(onResult: (String?) -> Unit) {
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            onResult(null)
            return
        }
        var finished = false
        lateinit var listener: NsdManager.DiscoveryListener
        fun complete(url: String?) {
            if (finished) return
            finished = true
            runCatching { nsd.stopServiceDiscovery(listener) }
            onResult(url)
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val port = info.port.takeIf { it > 0 } ?: 8790
                        complete("http://$host:$port")
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = complete(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching {
            nsd.discoverServices("_claudian._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            complete(null)
            return
        }
        lifecycleScope.launch {
            delay(4000)
            complete(null)
        }
    }

    private fun currentVersionCode(): Long {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    private fun checkForAppUpdate(manual: Boolean) {
        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                runCatching { api.getApkVersion() }.getOrNull()
            }
            if (remote == null) {
                if (manual) Toast.makeText(this@MainActivity, "Could not reach bridge for update check", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val localCode = currentVersionCode()
            if (remote.versionCode > localCode) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update Available")
                    .setMessage("Claudian Call v${remote.versionName} (build ${remote.versionCode}) is available. You are on build $localCode.")
                    .setPositiveButton("Download & Install") { _, _ ->
                        downloadAndInstallUpdate(remote.sha256)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (manual) {
                Toast.makeText(this@MainActivity, "Already up to date (v${remote.versionName}, build $localCode)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasMatchingSigningCertificate(apkFile: File): Boolean {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val currentInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val curSigs = currentInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
                val arcSigs = archiveInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
                curSigs.isNotEmpty() && curSigs == arcSigs
            } else {
                @Suppress("DEPRECATION")
                val curSigs = currentInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                @Suppress("DEPRECATION")
                val arcSigs = archiveInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                curSigs.isNotEmpty() && curSigs == arcSigs
            }
        }.getOrDefault(false)
    }

    private fun downloadAndInstallUpdate(expectedSha256: String = "") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow App Installs")
                .setMessage("Enable 'Allow from this source' for Claudian Call, then tap Check for App Update again.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val statusView = TextView(this).apply {
            text = "Downloading update... 0%"
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Updating Claudian Call")
            .setView(statusView)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val dest = File(cacheDir, "updates/claudian-call.apk")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    api.downloadApk(dest) { pct ->
                        runOnUiThread { statusView.text = "Downloading update... $pct%" }
                    }
                    if (expectedSha256.isNotBlank()) {
                        val actual = BridgeSecurity.sha256Hex(dest)
                        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                            dest.delete()
                            throw IllegalStateException("APK SHA-256 mismatch")
                        }
                    }
                    if (!hasMatchingSigningCertificate(dest)) {
                        dest.delete()
                        throw IllegalStateException("APK signing certificate mismatch")
                    }
                }
            }
            dlg.dismiss()
            if (result.isFailure) {
                Toast.makeText(
                    this@MainActivity,
                    "Update failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val apkUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", dest)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
