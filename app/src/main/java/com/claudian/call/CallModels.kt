package com.claudian.call

import android.content.Context

enum class CallPhase {
    IDLE,
    LISTENING,
    RECORDING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    MUTED
}

data class ChatMessage(
    val role: String,
    var text: String
)

data class BridgeSession(
    val id: String,
    val title: String,
    val sessionId: String?,
    val selectedModel: String,
    val lastActivityAt: Long
)

const val DEFAULT_VOICE_SYSTEM_PROMPT =
    "You are responding in a live voice call. Lead with the direct answer first, use short natural spoken sentences with clear signposts (First, Second, Next), refer to files by short name instead of full paths, avoid markdown tables, raw URLs, and code blocks unless asked, and end with one clear next step or single decision question."

data class CallSettings(
    val bridgeUrl: String = "http://127.0.0.1:8790",
    val authToken: String = "",
    val provider: String = "claude",
    val model: String = "haiku",
    val workspaceDir: String = "",
    val sttLanguage: String = "en",
    val kokoroVoice: String = "af_heart",
    val speechRate: Float = 1.05f,
    val vadThreshold: Float = 0.015f,
    val vadSilenceMs: Long = 1800L,
    val minSpeechMs: Long = 600L,
    val bargeIn: Boolean = false,
    val speakerOn: Boolean = false,
    val lastSessionId: String = "",
    val mobileSystemPrompt: String = DEFAULT_VOICE_SYSTEM_PROMPT
) {
    companion object {
        private const val PREFS = "claudian_call_prefs"

        fun load(ctx: Context): CallSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedVersion = p.getInt("prefsVersion", 1)
            val migrated = PrefsMigration.migrate(
                savedVersion,
                p.getLong("vadSilenceMs", 1800L),
                p.getLong("minSpeechMs", 600L),
                p.getBoolean("bargeIn", false)
            )
            val vadSilence = migrated.vadSilenceMs
            val minSpeech = migrated.minSpeechMs
            val bargeIn = migrated.bargeIn
            if (savedVersion < PrefsMigration.CURRENT_PREFS_VERSION) {
                p.edit()
                    .putInt("prefsVersion", PrefsMigration.CURRENT_PREFS_VERSION)
                    .putLong("vadSilenceMs", vadSilence)
                    .putLong("minSpeechMs", minSpeech)
                    .putBoolean("bargeIn", bargeIn)
                    .apply()
            }
            return CallSettings(
                bridgeUrl = p.getString("bridgeUrl", "http://127.0.0.1:8790") ?: "http://127.0.0.1:8790",
                authToken = p.getString("authToken", "") ?: "",
                provider = p.getString("provider", "claude") ?: "claude",
                model = p.getString("model", "haiku") ?: "haiku",
                workspaceDir = p.getString("workspaceDir", "") ?: "",
                sttLanguage = p.getString("sttLanguage", "en") ?: "en",
                kokoroVoice = p.getString("kokoroVoice", "af_heart") ?: "af_heart",
                speechRate = p.getFloat("speechRate", 1.05f),
                vadThreshold = p.getFloat("vadThreshold", 0.015f),
                vadSilenceMs = vadSilence,
                minSpeechMs = minSpeech,
                bargeIn = bargeIn,
                speakerOn = p.getBoolean("speakerOn", false),
                lastSessionId = p.getString("lastSessionId", "") ?: "",
                mobileSystemPrompt = p.getString("mobileSystemPrompt", DEFAULT_VOICE_SYSTEM_PROMPT) ?: ""
            )
        }

        fun save(ctx: Context, s: CallSettings) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("bridgeUrl", s.bridgeUrl.trim().trimEnd('/'))
                .putString("authToken", s.authToken.trim())
                .putString("provider", s.provider)
                .putString("model", s.model)
                .putString("workspaceDir", s.workspaceDir)
                .putString("sttLanguage", s.sttLanguage)
                .putString("kokoroVoice", s.kokoroVoice)
                .putFloat("speechRate", s.speechRate)
                .putFloat("vadThreshold", s.vadThreshold)
                .putLong("vadSilenceMs", s.vadSilenceMs)
                .putLong("minSpeechMs", s.minSpeechMs)
                .putBoolean("bargeIn", s.bargeIn)
                .putBoolean("speakerOn", s.speakerOn)
                .putString("lastSessionId", s.lastSessionId)
                .putString("mobileSystemPrompt", s.mobileSystemPrompt)
                .apply()
        }
    }
}

/** Pure core of the SharedPreferences version migration (no Android dependencies). */
object PrefsMigration {
    const val CURRENT_PREFS_VERSION = 2

    data class VadPrefs(val vadSilenceMs: Long, val minSpeechMs: Long, val bargeIn: Boolean)

    fun migrate(savedVersion: Int, vadSilenceMs: Long, minSpeechMs: Long, bargeIn: Boolean): VadPrefs {
        if (savedVersion >= CURRENT_PREFS_VERSION) return VadPrefs(vadSilenceMs, minSpeechMs, bargeIn)
        var vadSilence = vadSilenceMs
        var minSpeech = minSpeechMs
        if (vadSilence < 1200L) vadSilence = 1800L
        if (minSpeech < 500L) minSpeech = 600L
        return VadPrefs(vadSilence, minSpeech, false)
    }
}

object SpeechTextUtils {
    private val SENTENCE_REGEX = Regex("""[^.!?\n]+(?:[.!?]+(?=\s|$)|\n+|$)""")

    fun stripMarkdownForSpeech(text: String): String {
        return text
            .replace(Regex("""```[\s\S]*?```"""), " (see screen for code) ")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "a link")
            .replace(Regex("""(?:/[\w.-]+){2,}/([\w.-]+)"""), "$1")
            .replace(Regex("""(?m)^\|?[\s:-]+\|[\s|:-]*$"""), "")
            .replace(Regex("""(?m)^\|(.+)\|$""")) { m ->
                m.groupValues[1].split("|").joinToString(", ") { it.trim() }
            }
            .replace(Regex("""(?m)^#{1,6}\s+"""), "")
            .replace(Regex("""(?m)^>\s?"""), "")
            .replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
            .replace(Regex("""(\*\*\*|___)(.+?)\1"""), "$2")
            .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
            .replace(Regex("""(\*|_)(.+?)\1"""), "$2")
            .replace(Regex("""~~(.+?)~~"""), "$1")
            .replace(Regex("""[*_`~]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun extractCompletedSentences(text: String, cursor: Int): Pair<List<String>, Int> {
        if (cursor >= text.length) return emptyList<String>() to cursor
        val slice = text.substring(cursor)
        val out = mutableListOf<String>()
        var consumed = 0
        for (match in SENTENCE_REGEX.findAll(slice)) {
            val chunk = match.value
            val trimmedEnd = chunk.trimEnd(' ', '\t')
            val endsWithBoundary = trimmedEnd.endsWith('.') ||
                trimmedEnd.endsWith('!') ||
                trimmedEnd.endsWith('?') ||
                trimmedEnd.endsWith('\n')
            val isAtEnd = match.range.last + 1 == slice.length
            if (endsWithBoundary && !isAtEnd) {
                val cleaned = chunk.trim()
                if (cleaned.isNotEmpty()) out.add(cleaned)
                consumed = match.range.last + 1
            } else if (!isAtEnd) {
                consumed = match.range.last + 1
            } else {
                break
            }
        }
        return out to (cursor + consumed)
    }
}
