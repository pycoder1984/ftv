package com.vidking.firetv.scraper

import android.util.Base64
import android.util.Log
import com.vidking.firetv.febbox.ResolvedStream
import com.vidking.firetv.wyzie.WyzieRepository

/**
 * VidZee (player.vidzee.wtf) — multi-server resolver. Calls the JSON API
 * with sr=1..6 until one returns. The body is a base64-encoded blob whose
 * first 16 bytes are the IV and remainder is AES-256-CBC ciphertext, key
 * is `qrincywincyspider` (UTF-8, NUL-padded to 32 bytes). Plaintext is
 * the m3u8 URL.
 */
internal object VidZeeScraper : Scraper {

    private const val TAG = "VidZeeScraper"
    private const val API_BASE = "https://player.vidzee.wtf/api/server"
    private const val REFERER = "https://core.vidzee.wtf/"
    private const val MAX_SERVERS = 6

    override val id: String = "vidzee"
    override val displayName: String = "VidZee"

    private val key: ByteArray = Crypto.pad32("qrincywincyspider")

    // Field name varies across upstream versions — try them in order.
    private val cipherFieldRe = Regex("""["'](?:url|data|encrypted|stream|src)["']\s*:\s*["']([^"']+)["']""")

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): ResolvedStream? {
        for (sr in 1..MAX_SERVERS) {
            val url = buildString {
                append(API_BASE)
                append("?id=").append(tmdbId)
                append("&sr=").append(sr)
                if (mediaType == "tv") {
                    append("&ss=").append(season)
                    append("&ep=").append(episode)
                }
            }
            val body = try {
                ScraperHttp.getText(url, ScraperHttp.desktopHeaders(referer = REFERER))
            } catch (t: Throwable) {
                Log.d(TAG, "sr=$sr request failed: ${t.javaClass.simpleName}")
                continue
            }
            val plaintext = decrypt(body) ?: continue
            val streamUrl = pickStreamUrl(plaintext) ?: continue
            Log.d(TAG, "resolved via sr=$sr")

            val subs = WyzieRepository.fetch(tmdbId, mediaType, season, episode)
            return ResolvedStream(
                url = streamUrl,
                referer = REFERER,
                userAgent = ScraperHttp.DESKTOP_USER_AGENT,
                subtitles = subs,
                introStartMs = -1L,
                introEndMs = -1L
            )
        }
        return null
    }

    private fun decrypt(body: String): String? {
        val candidate = extractCipherCandidate(body) ?: return null
        return try {
            val combined = Base64.decode(candidate, Base64.DEFAULT)
            if (combined.size <= 16) return null
            val iv = combined.copyOfRange(0, 16)
            val cipher = combined.copyOfRange(16, combined.size)
            String(Crypto.aesCbcDecrypt(cipher, key, iv), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.d(TAG, "decrypt failed: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun extractCipherCandidate(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        // JSON-shaped response: pull the first field that looks like the cipher.
        cipherFieldRe.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        // Plain base64 body — strip surrounding quotes if present.
        return trimmed.trim('"')
    }

    private fun pickStreamUrl(plaintext: String): String? {
        // Plaintext is sometimes JSON ({ "url": "...", "track": [...] }) and
        // sometimes the raw URL. Try both shapes.
        val direct = plaintext.trim().trim('"')
        if (direct.startsWith("https://") || direct.startsWith("http://")) {
            return direct
        }
        return cipherFieldRe.find(plaintext)?.groupValues?.getOrNull(1)
    }
}
