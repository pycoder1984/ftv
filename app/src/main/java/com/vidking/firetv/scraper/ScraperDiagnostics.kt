package com.vidking.firetv.scraper

/**
 * Bounded ring buffer of recent scraper attempts. Surfaced in the
 * `ExoPlayerActivity` debug overlay (long-press OK) and on the
 * `PlaybackLauncherActivity` failure screen so we can see, on a TV,
 * which scrapers ran and how they failed.
 */
internal object ScraperDiagnostics {

    data class Attempt(
        val scraperId: String,
        val scraperName: String,
        val durationMs: Long,
        val success: Boolean,
        val errorClass: String? = null,
        val errorMsg: String? = null
    )

    private const val MAX = 24
    private val deque: ArrayDeque<Attempt> = ArrayDeque()
    private val lock = Any()

    fun record(a: Attempt) {
        synchronized(lock) {
            deque.addLast(a)
            while (deque.size > MAX) deque.removeFirst()
        }
    }

    fun snapshot(): List<Attempt> = synchronized(lock) { deque.toList() }

    fun summary(): String = snapshot().joinToString("\n") { a ->
        if (a.success) {
            "✓ ${a.scraperName} (${a.durationMs}ms)"
        } else {
            val tail = listOfNotNull(a.errorClass, a.errorMsg).joinToString(": ").take(80)
            "✗ ${a.scraperName} (${a.durationMs}ms) $tail"
        }
    }
}
