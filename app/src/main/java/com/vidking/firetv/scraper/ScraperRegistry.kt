package com.vidking.firetv.scraper

import android.content.Context
import android.util.Log
import com.vidking.firetv.data.AppPrefs
import com.vidking.firetv.febbox.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs all enabled scrapers in parallel and returns the first one to
 * produce a non-null [ResolvedStream]. Siblings are cancelled as soon as
 * a winner is found. Each scraper is wrapped in its own per-call timeout
 * so a single hung site can't blow the overall budget.
 */
internal object ScraperRegistry {

    private const val TAG = "ScraperRegistry"

    /** Per-scraper hard cap. Most resolutions complete in 1-3 s. */
    const val PER_SCRAPER_TIMEOUT_MS: Long = 12_000L

    /** Overall wall-clock budget for the whole stage. */
    const val OVERALL_BUDGET_MS: Long = 14_000L

    val ALL: List<Scraper> = listOf(
        VixSrcScraper,
        VidZeeScraper,
        VidSrcScraper
    )

    suspend fun resolveFirst(
        context: Context,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): Pair<Scraper, ResolvedStream>? {
        val active = ALL.filter { AppPrefs.scraperEnabled(context, it.id) }
        if (active.isEmpty()) {
            Log.d(TAG, "no scrapers enabled")
            return null
        }
        Log.d(TAG, "running ${active.size} scrapers in parallel: ${active.joinToString { it.id }}")

        return withTimeoutOrNull(OVERALL_BUDGET_MS) {
            coroutineScope {
                val deferreds = active.map { scraper ->
                    async { runOne(scraper, tmdbId, mediaType, season, episode) }
                }
                try {
                    var pending = deferreds.toList()
                    while (pending.isNotEmpty()) {
                        val first = select<Pair<Scraper, ResolvedStream>?> {
                            pending.forEach { d -> d.onAwait { it } }
                        }
                        if (first != null) return@coroutineScope first
                        pending = pending.filter { !it.isCompleted }
                    }
                    null
                } finally {
                    deferreds.forEach { it.cancel() }
                }
            }
        }
    }

    private suspend fun runOne(
        scraper: Scraper,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): Pair<Scraper, ResolvedStream>? {
        val start = System.currentTimeMillis()
        val stream: ResolvedStream? = try {
            withTimeoutOrNull(PER_SCRAPER_TIMEOUT_MS) {
                scraper.resolve(tmdbId, mediaType, season, episode)
            }
        } catch (ce: CancellationException) {
            // Sibling won — propagate cancellation so we don't record bogus errors.
            throw ce
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            ScraperDiagnostics.record(
                ScraperDiagnostics.Attempt(
                    scraperId = scraper.id,
                    scraperName = scraper.displayName,
                    durationMs = elapsed,
                    success = false,
                    errorClass = t.javaClass.simpleName,
                    errorMsg = t.message
                )
            )
            Log.w(TAG, "${scraper.id} failed in ${elapsed}ms: ${t.javaClass.simpleName} ${t.message}")
            return null
        }
        val elapsed = System.currentTimeMillis() - start
        return if (stream != null) {
            ScraperDiagnostics.record(
                ScraperDiagnostics.Attempt(
                    scraperId = scraper.id,
                    scraperName = scraper.displayName,
                    durationMs = elapsed,
                    success = true
                )
            )
            Log.d(TAG, "${scraper.id} resolved in ${elapsed}ms")
            scraper to stream
        } else {
            ScraperDiagnostics.record(
                ScraperDiagnostics.Attempt(
                    scraperId = scraper.id,
                    scraperName = scraper.displayName,
                    durationMs = elapsed,
                    success = false,
                    errorMsg = "no stream"
                )
            )
            Log.d(TAG, "${scraper.id} returned null in ${elapsed}ms")
            null
        }
    }
}
