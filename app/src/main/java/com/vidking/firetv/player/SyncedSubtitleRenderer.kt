package com.vidking.firetv.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.media3.common.Player
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Renders sidecar subtitles ourselves so the user can apply a time offset
 * without re-preparing ExoPlayer (which would cause a multi-second buffer
 * stall on every adjustment).
 *
 * Flow:
 *   1. Caller hands us one or more SRT / VTT URLs.
 *   2. We fetch each into memory and parse the cue table.
 *   3. A 100 ms ticker reads `player.currentPosition`, applies the user's
 *      offset, and updates a single overlay TextView with the active cue.
 *
 * The OpenSubtitles tracks are stripped from ExoPlayer's MediaItem so we
 * own the display end-to-end; embedded HLS captions still go through the
 * player's normal subtitle pipeline.
 */
class SyncedSubtitleRenderer(
    private val overlayView: TextView,
) {
    data class Cue(val startMs: Long, val endMs: Long, val text: CharSequence)

    private var cues: List<Cue> = emptyList()
    private var offsetMs: Long = 0
    private var enabled: Boolean = true
    private var hasCues: Boolean = false
    private var player: Player? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateCue()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun attach(player: Player) {
        this.player = player
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun detach() {
        handler.removeCallbacks(tick)
        player = null
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            overlayView.text = ""
            overlayView.visibility = View.GONE
        } else if (hasCues) {
            overlayView.visibility = View.VISIBLE
            updateCue()
        }
    }

    fun setOffsetMs(ms: Long) {
        offsetMs = ms
        updateCue()
    }

    fun offsetMs(): Long = offsetMs

    fun hasLoadedCues(): Boolean = hasCues

    /**
     * Loads subtitle content from [url], parses it, and stores the cue list.
     * Returns true on success. Runs on the caller's thread — caller is
     * expected to invoke this from a background dispatcher.
     */
    fun loadFromUrl(url: String): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "subtitle fetch HTTP ${resp.code} from $url")
                return@use false
            }
            val body = resp.body?.string().orEmpty()
            val parsed = parse(body)
            if (parsed.isEmpty()) {
                Log.w(TAG, "no cues parsed from $url (${body.length} bytes)")
                return@use false
            }
            handler.post {
                cues = parsed
                hasCues = true
                if (enabled) overlayView.visibility = View.VISIBLE
                updateCue()
            }
            Log.d(TAG, "loaded ${parsed.size} cues from $url")
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "subtitle load failed for $url: ${t.javaClass.simpleName} ${t.message}")
        false
    }

    private fun updateCue() {
        if (!enabled || !hasCues) return
        val pos = (player?.currentPosition ?: 0L) - offsetMs
        // cues are sorted by start; linear scan is fine for typical sizes
        // (~1k cues). Could binary-search if it ever shows up in profiling.
        val active = cues.firstOrNull { pos in it.startMs..it.endMs }
        overlayView.text = active?.text ?: ""
    }

    /**
     * Lenient SRT / WebVTT parser. Accepts cue-number prefixes (SRT
     * convention), missing prefixes (some VTTs), `,` or `.` as the ms
     * separator, and stray positioning hints after the timestamp line.
     */
    private fun parse(content: String): List<Cue> {
        // WebVTT files start with "WEBVTT" — skip the header block.
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("\\n{2,}"))
        val out = ArrayList<Cue>(blocks.size)
        for (block in blocks) {
            val lines = block.split('\n').filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // Find the timestamp line — it's the first line containing `-->`.
            val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIdx < 0) continue
            val m = TIME_RE.find(lines[timeLineIdx]) ?: continue
            val start = toMs(
                m.groupValues[1], m.groupValues[2],
                m.groupValues[3], m.groupValues[4]
            )
            val end = toMs(
                m.groupValues[5], m.groupValues[6],
                m.groupValues[7], m.groupValues[8]
            )
            val rawText = lines.drop(timeLineIdx + 1).joinToString("\n").trim()
            if (rawText.isEmpty()) continue
            // SRT/VTT support `<i>`, `<b>`, `<u>` — render with HtmlCompat so
            // tags don't show as literal angle brackets.
            val rendered = HtmlCompat.fromHtml(rawText, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString().trim()
            if (rendered.isNotEmpty()) out += Cue(start, end, rendered)
        }
        return out.sortedBy { it.startMs }
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val msPadded = (ms + "000").substring(0, 3)
        return h.toLong() * 3_600_000L +
            m.toLong() * 60_000L +
            s.toLong() * 1_000L +
            msPadded.toLong()
    }

    companion object {
        private const val TAG = "SyncedSubs"
        private const val TICK_MS = 100L
        private const val USER_AGENT = "Vidking FireTV v1.0"

        private val TIME_RE = Regex(
            """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
        )

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
