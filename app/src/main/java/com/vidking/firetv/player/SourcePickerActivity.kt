package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.R
import com.vidking.firetv.data.AppPrefs
import com.vidking.firetv.febbox.FebboxRepository
import com.vidking.firetv.febbox.ResolvedStream
import com.vidking.firetv.febbox.StreamVariant
import com.vidking.firetv.febbox.SubtitleTrack
import com.vidking.firetv.opensubtitles.OpenSubtitlesRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Source -> (optional quality) -> play. Replaces the old auto-cascade launcher.
 * The user picks which provider to play from; if the provider returns multiple
 * quality variants (Febbox does, sniffer providers don't), a second step lets
 * them pick which one. In parallel with the source resolve we fetch English
 * subtitles from OpenSubtitles and attach them to the ExoPlayer MediaItem.
 *
 * Stages:
 *   1. SOURCE_LIST — buttons for Febbox (if configured) + every embed provider
 *   2. RESOLVING   — title + spinner + status text; the hidden WebView host
 *                    underneath is what the sniffer actually paints into
 *   3. QUALITY_LIST — only shown when Febbox returns a non-trivial ladder
 *
 * Failures bring the user back to SOURCE_LIST with a one-line error so they
 * can try another provider.
 */
class SourcePickerActivity : AppCompatActivity() {

    private enum class Stage { SOURCE_LIST, RESOLVING, QUALITY_LIST }

    private data class Source(
        val id: String,
        val name: String,
        val subtitle: String,
        val kind: Kind
    ) {
        enum class Kind { FEBBOX, SNIFFER }
    }

    private lateinit var rootView: FrameLayout
    private lateinit var snifferHost: FrameLayout
    private lateinit var contentLayer: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var listColumn: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var backHint: TextView

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0

    private var currentJob: Job? = null
    private var resolvedStream: ResolvedStream? = null
    private var resolvedSource: Source? = null
    private var stage: Stage = Stage.SOURCE_LIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        titleArg = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        posterPath = intent.getStringExtra(EXTRA_POSTER)
        backdropPath = intent.getStringExtra(EXTRA_BACKDROP)
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)

        buildUi()
        showSourceList()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (stage) {
                    Stage.QUALITY_LIST -> showSourceList()
                    Stage.RESOLVING -> {
                        currentJob?.cancel()
                        showSourceList()
                    }
                    Stage.SOURCE_LIST -> finish()
                }
            }
        })
    }

    private fun buildUi() {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Hidden host for the sniffer's WebView. MATCH_PARENT so its viewport
        // looks real to anti-bot checks; INVISIBLE keeps it under the picker.
        snifferHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
        }
        rootView.addView(snifferHost)

        // The picker / resolving / quality UI sits on top of the WebView host.
        contentLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(contentLayer)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(48), dp(64), dp(48))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutParams = lp
        }

        titleText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        column.addView(titleText)

        subtitleText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        column.addView(subtitleText)

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(16)
            layoutParams = lp
            visibility = View.GONE
        }
        column.addView(spinner)

        statusText = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
            visibility = View.GONE
        }
        column.addView(statusText)

        // Scrollable list of focusable buttons. With 7+ sources or a tall
        // quality ladder, the column would overflow the screen — wrap it.
        val scroll = ScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp
            isVerticalScrollBarEnabled = false
        }
        listColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(listColumn)
        column.addView(scroll)

        backHint = TextView(this).apply {
            text = getString(R.string.picker_back_hint)
            setTextColor(0xAAFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            visibility = View.GONE
        }
        column.addView(backHint)

        contentLayer.addView(column)
        setContentView(rootView)
    }

    // ---------- stages ----------

    private fun showSourceList() {
        stage = Stage.SOURCE_LIST
        currentJob?.cancel()
        currentJob = null
        snifferHost.removeAllViews()
        snifferHost.visibility = View.INVISIBLE
        contentLayer.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        backHint.visibility = View.GONE

        titleText.text = titleArg.ifEmpty { getString(R.string.picker_source_title) }
        subtitleText.text = getString(R.string.picker_source_subtitle)
        statusText.visibility = View.GONE

        val sources = buildSourceList()
        listColumn.removeAllViews()
        sources.forEachIndexed { index, source ->
            val btn = makeListButton(
                title = source.name,
                subtitle = source.subtitle
            ) {
                onSourceClicked(source)
            }
            listColumn.addView(btn)
            if (index == 0) btn.post { btn.requestFocus() }
        }
    }

    private fun showResolving(source: Source) {
        stage = Stage.RESOLVING
        snifferHost.visibility = if (source.kind == Source.Kind.SNIFFER) View.VISIBLE else View.INVISIBLE
        contentLayer.visibility = View.VISIBLE

        titleText.text = titleArg
        subtitleText.text = getString(R.string.picker_resolving, source.name)
        spinner.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = source.name
        listColumn.removeAllViews()
        backHint.text = getString(R.string.picker_back_hint)
        backHint.visibility = View.VISIBLE
    }

    private fun showQualityList(source: Source, stream: ResolvedStream) {
        stage = Stage.QUALITY_LIST
        snifferHost.visibility = View.INVISIBLE
        snifferHost.removeAllViews()
        contentLayer.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        statusText.visibility = View.GONE

        titleText.text = titleArg
        subtitleText.text = getString(R.string.picker_quality_subtitle, source.name)
        listColumn.removeAllViews()

        val playable = stream.variants.filter { !it.isAudioOnly }
        playable.forEachIndexed { index, variant ->
            val btn = makeListButton(
                title = variant.quality.ifBlank { "AUTO" },
                subtitle = variant.codec.takeIf { it.isNotBlank() }
                    ?.let { "Codec: $it" } ?: ""
            ) { launchPlayer(source, stream, variant) }
            listColumn.addView(btn)
            if (index == 0) btn.post { btn.requestFocus() }
        }
        backHint.visibility = View.VISIBLE
    }

    private fun showFailure(source: Source, error: String?) {
        showSourceList()
        statusText.text = error?.takeIf { it.isNotBlank() }
            ?.let { "${source.name}: $it" }
            ?: getString(R.string.picker_failed, source.name)
        statusText.visibility = View.VISIBLE
    }

    // ---------- actions ----------

    private fun buildSourceList(): List<Source> {
        val out = mutableListOf<Source>()
        if (AppPrefs.hasFebboxConfig(this)) {
            out += Source(
                id = "febbox",
                name = "Febbox",
                subtitle = "",
                kind = Source.Kind.FEBBOX
            )
        }
        StreamProviders.ALL.forEach { p ->
            out += Source(
                id = p.id,
                name = p.name,
                subtitle = "",
                kind = Source.Kind.SNIFFER
            )
        }
        return out
    }

    private fun onSourceClicked(source: Source) {
        showResolving(source)
        currentJob = lifecycleScope.launch {
            // Kick off subtitle fetch in parallel with the source resolve so the
            // user doesn't wait twice. OpenSubtitles is best-effort; failures
            // return an empty list rather than blocking playback.
            val subsDeferred = async(start = CoroutineStart.LAZY) {
                OpenSubtitlesRepository.fetch(tmdbId, mediaType, season, episode)
            }

            val streamResult = when (source.kind) {
                Source.Kind.FEBBOX -> resolveFebbox()
                Source.Kind.SNIFFER -> resolveSniffer(source.id)
            }

            streamResult.fold(
                onSuccess = { stream ->
                    // Subtitle fetch races the user's quality pick. If they
                    // pick instantly, we still want to wait briefly so subs
                    // attach before the player starts.
                    val subs = if (stream.hasQualityChoices) {
                        // Detach so quality picker isn't blocked on subs.
                        // launchPlayer awaits later.
                        subsDeferred.start()
                        emptyList()
                    } else {
                        awaitAll(subsDeferred).firstOrNull().orEmpty()
                    }

                    resolvedStream = stream.copy(subtitles = subs)
                    resolvedSource = source

                    if (stream.hasQualityChoices) {
                        showQualityList(source, resolvedStream!!)
                        // Quality pick path: stash the in-flight subs deferred
                        // on the activity scope so launchPlayer() can await it.
                        pendingSubtitlesJob = subsDeferred
                    } else {
                        launchPlayer(source, resolvedStream!!, stream.variants.first())
                    }
                },
                onFailure = { e ->
                    showFailure(source, e.message ?: e.javaClass.simpleName)
                }
            )
        }
    }

    private suspend fun resolveFebbox(): Result<ResolvedStream> =
        FebboxRepository.resolve(this, tmdbId, mediaType, season, episode)

    private suspend fun resolveSniffer(providerId: String): Result<ResolvedStream> {
        val provider = StreamProviders.ALL.firstOrNull { it.id == providerId }
            ?: return Result.failure(IllegalStateException("Unknown provider $providerId"))
        val embedUrl = provider.builder(tmdbId, mediaType, season, episode, 0L)
        val sniffer = StreamSniffer(this)
        val sniffed = withTimeoutOrNull(25_000L) {
            sniffer.sniff(embedUrl, snifferHost, timeoutMs = 24_000L)
        } ?: return Result.failure(IllegalStateException("Sniff timed out"))

        return Result.success(
            ResolvedStream(
                variants = listOf(
                    StreamVariant(url = sniffed.url, quality = "AUTO")
                ),
                referer = sniffed.referer,
                userAgent = sniffed.userAgent,
                subtitles = emptyList()
            )
        )
    }

    private var pendingSubtitlesJob: kotlinx.coroutines.Deferred<List<SubtitleTrack>>? = null

    private fun launchPlayer(
        source: Source,
        stream: ResolvedStream,
        variant: StreamVariant
    ) {
        currentJob = lifecycleScope.launch {
            val subs = stream.subtitles.takeIf { it.isNotEmpty() }
                ?: pendingSubtitlesJob?.let {
                    // Give the in-flight fetch up to 5 s; otherwise play without subs.
                    withTimeoutOrNull(5_000L) { it.await() }
                }
                ?: emptyList()

            val intent = ExoPlayerActivity.intent(
                context = this@SourcePickerActivity,
                streamUrl = variant.url,
                referer = stream.referer,
                userAgent = stream.userAgent,
                tmdbId = tmdbId,
                mediaType = mediaType,
                title = titleArg,
                posterPath = posterPath,
                backdropPath = backdropPath,
                season = season,
                episode = episode,
                subtitles = subs,
                sourceLabel = "${source.name} • ${variant.quality}"
            )
            startActivity(intent)
            finish()
        }
    }

    // ---------- ui helpers ----------

    private fun makeListButton(
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = listButtonBackground()
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        container.addView(titleView)
        if (subtitle.isNotBlank()) {
            val sub = TextView(this).apply {
                text = subtitle
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, 0)
            }
            container.addView(sub)
        }
        return container
    }

    private fun listButtonBackground(): StateListDrawable {
        val unfocused = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(0x22FFFFFF)
            setStroke(dp(1), 0x33FFFFFF)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(0xCCE50914.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), unfocused)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        currentJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_MEDIA_TYPE = "media_type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_BACKDROP = "backdrop"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"

        fun intent(
            context: Context,
            tmdbId: Int,
            mediaType: String,
            title: String,
            posterPath: String?,
            backdropPath: String?,
            season: Int = 0,
            episode: Int = 0
        ): Intent = Intent(context, SourcePickerActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER, posterPath)
            putExtra(EXTRA_BACKDROP, backdropPath)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
        }
    }
}
