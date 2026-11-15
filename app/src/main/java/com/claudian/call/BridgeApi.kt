package com.claudian.call

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class ApkVersionInfo(
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long
)

data class ModelOption(
    val id: String,
    val label: String
)

data class WorkspaceOption(
    val path: String,
    val name: String
)

class BridgeApi(private val getSettings: () -> CallSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String = getSettings().bridgeUrl.trim().trimEnd('/')

    private fun Request.Builder.withAuth(): Request.Builder {
        val tok = getSettings().authToken.trim()
        if (tok.isNotEmpty()) header("Authorization", "Bearer $tok")
        return this
    }

    private fun <T> withRetry(retries: Int = 2, block: () -> T): T {
        var lastErr: Throwable? = null
        for (attempt in 0..retries) {
            try {
                return block()
            } catch (t: Throwable) {
                lastErr = t
                val msg = t.message ?: ""
                if (attempt == retries || Regex("""\b(400|401|403|404)\b""").containsMatchIn(msg)) {
                    throw t
                }
                Thread.sleep((500L * (attempt + 1)))
            }
        }
        throw lastErr ?: IOException("Request failed")
    }

    fun health(): Boolean = withRetry(1) {
        val req = Request.Builder()
            .url("${baseUrl()}/health")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.optBoolean("ok", false)
        }
    }

    fun getApkVersion(): ApkVersionInfo {
        val req = Request.Builder()
            .url("${baseUrl()}/apk/version")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            return ApkVersionInfo(
                versionCode = json.optLong("versionCode", 1L),
                versionName = json.optString("versionName", "0.1.0"),
                sizeBytes = json.optLong("size", 0L)
            )
        }
    }

    fun downloadApk(destFile: File, onProgress: (Int) -> Unit) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        val req = Request.Builder()
            .url("${baseUrl()}/apk")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty APK body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16384)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        if (destFile.exists()) destFile.delete()
        if (!tmp.renameTo(destFile)) {
            throw IOException("Failed to finalize downloaded APK")
        }
    }

    fun listWorkspaces(): List<WorkspaceOption> = withRetry(1) {
        val req = Request.Builder()
            .url("${baseUrl()}/workspaces")
            .withAuth()
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("workspaces") ?: JSONArray()
            val out = mutableListOf<WorkspaceOption>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(WorkspaceOption(o.optString("path", ""), o.optString("name", "")))
            }
            out
        }
    }

    fun createWorkspace(name: String): WorkspaceOption {
        val body = JSONObject().put("name", name.trim()).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${baseUrl()}/workspaces")
            .withAuth()
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            val json = JSONObject(raw)
            if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "HTTP ${resp.code}"))
            }
            val w = json.getJSONObject("workspace")
            return WorkspaceOption(w.optString("path", ""), w.optString("name", ""))
        }
    }

    fun listModels(provider: String): List<ModelOption> = withRetry(1) {
        val enc = URLEncoder.encode(provider, "UTF-8")
        val req = Request.Builder()
            .url("${baseUrl()}/models?provider=$enc")
            .withAuth()
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("models") ?: JSONArray()
            val out = mutableListOf<ModelOption>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ModelOption(o.optString("id", ""), o.optString("label", "")))
            }
            out
        }
    }

    fun listSessions(cwd: String? = null): List<BridgeSession> = withRetry {
        val qs = if (!cwd.isNullOrBlank()) "?cwd=${URLEncoder.encode(cwd, "UTF-8")}" else ""
        val req = Request.Builder()
            .url("${baseUrl()}/sessions$qs")
            .withAuth()
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("sessions") ?: JSONArray()
            val out = mutableListOf<BridgeSession>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BridgeSession(
                        id = o.optString("id"),
                        title = o.optString("title", "Conversation"),
                        sessionId = o.optString("sessionId").takeIf { it.isNotEmpty() && it != "null" },
                        selectedModel = o.optString("selectedModel", "haiku"),
                        lastActivityAt = o.optLong("lastActivityAt", 0L)
                    )
                )
            }
            out
        }
    }

    fun recapSession(sessionId: String): String {
        val encoded = URLEncoder.encode(sessionId, "UTF-8")
        val req = Request.Builder()
            .url("${baseUrl()}/sessions/$encoded/recap")
            .withAuth()
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            val json = JSONObject(raw)
            if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "HTTP ${resp.code}"))
            }
            return json.optString("recap", "")
        }
    }

    fun saveToVault(title: String, content: String, workspace: String, sessionId: String?): String {
        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("workspace", workspace)
        if (!sessionId.isNullOrBlank()) payload.put("sessionId", sessionId)
        val req = Request.Builder()
            .url("${baseUrl()}/vault/save")
            .withAuth()
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            val json = JSONObject(raw)
            if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "HTTP ${resp.code}"))
            }
            return json.optString("relativePath", "Saved")
        }
    }

    fun getSessionMessages(sessionId: String): List<ChatMessage> = withRetry {
        val encoded = URLEncoder.encode(sessionId, "UTF-8")
        val req = Request.Builder()
            .url("${baseUrl()}/sessions/$encoded/messages")
            .withAuth()
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val out = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ChatMessage(o.optString("role", "assistant"), o.optString("text", "")))
            }
            out
        }
    }

    fun transcribeWav(wavBytes: ByteArray): String = withRetry {
        val lang = URLEncoder.encode(getSettings().sttLanguage.ifBlank { "en" }, "UTF-8")
        val req = Request.Builder()
            .url("${baseUrl()}/stt?language=$lang")
            .withAuth()
            .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("STT HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "STT failed"))
            }
            json.optString("text", "").trim()
        }
    }

    fun synthesizeTts(text: String): ByteArray = withRetry {
        val s = getSettings()
        val bodyJson = JSONObject()
            .put("text", text)
            .put("voice", s.kokoroVoice.ifBlank { "af_heart" })
            .put("speed", s.speechRate.toDouble())
        val req = Request.Builder()
            .url("${baseUrl()}/tts")
            .withAuth()
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("TTS HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("Empty TTS body")
        }
    }

    fun streamChat(
        prompt: String,
        sessionId: String?,
        onCallCreated: (Call) -> Unit,
        onSession: (String) -> Unit,
        onStatus: (String) -> Unit = {},
        onText: (String) -> Unit,
        onDone: (String?, String) -> Unit
    ) {
        val s = getSettings()
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("provider", s.provider.ifBlank { "claude" })
            .put("workflow", s.workflow.ifBlank { "general" })
        if (s.model.isNotBlank()) payload.put("model", s.model.trim())
        if (s.workspaceDir.isNotBlank()) payload.put("cwd", s.workspaceDir.trim())
        if (!sessionId.isNullOrBlank()) payload.put("sessionId", sessionId)
        if (s.mobileSystemPrompt.isNotBlank()) payload.put("systemPrompt", s.mobileSystemPrompt.trim())

        val req = Request.Builder()
            .url("${baseUrl()}/chat")
            .withAuth()
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = streamClient.newCall(req)
        onCallCreated(call)

        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Chat HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("Empty chat stream")
            var eventName = "message"
            var dataLine = ""
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (dataLine.isNotEmpty()) {
                        dispatchSse(eventName, dataLine, onSession, onStatus, onText, onDone)
                    }
                    eventName = "message"
                    dataLine = ""
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    dataLine = line.substring(5).trim()
                }
            }
            if (dataLine.isNotEmpty()) {
                dispatchSse(eventName, dataLine, onSession, onStatus, onText, onDone)
            }
        }
    }

    private fun dispatchSse(
        eventName: String,
        dataLine: String,
        onSession: (String) -> Unit,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onDone: (String?, String) -> Unit
    ) {
        val payload = JSONObject(dataLine)
        when (eventName) {
            "session" -> {
                val sid = payload.optString("sessionId")
                if (sid.isNotEmpty()) onSession(sid)
            }
            "status" -> {
                val st = payload.optString("status")
                if (st.isNotEmpty()) onStatus(st)
            }
            "text" -> {
                onText(payload.optString("fullText", ""))
            }
            "done" -> {
                val sid = payload.optString("sessionId").takeIf { it.isNotEmpty() && it != "null" }
                onDone(sid, payload.optString("text", ""))
            }
            "error" -> {
                throw IOException(payload.optString("error", "Chat error"))
            }
        }
    }

    companion object {
        fun pcm16ToWav(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
            val dataBytes = pcm.size * 2
            val out = ByteArrayOutputStream(44 + dataBytes)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            out.write(header.array())
            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort(s)
            out.write(buf.array())
            return out.toByteArray()
        }
    }
}
