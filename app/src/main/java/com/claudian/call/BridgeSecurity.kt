package com.claudian.call

import java.io.File
import java.net.URI
import java.security.MessageDigest

object BridgeSecurity {
    fun isAllowedBridgeUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme == "https") return true
        if (scheme != "http") return false

        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.endsWith(".local") || host.endsWith(".ts.net")) return true

        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        if (a == 10) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 100 && b in 64..127) return true // Tailscale CGNAT 100.64.0.0/10
        return false
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
