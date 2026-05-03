package com.vidking.firetv.details

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vidking.firetv.R
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import com.vidking.firetv.player.PlaybackLauncherActivity
import com.vidking.firetv.presenters.CardPresenter
import com.vidking.firetv.tmdb.Episode
import com.vidking.firetv.tmdb.MovieDetails
import com.vidking.firetv.tmdb.Tmdb
import com.vidking.firetv.tmdb.TvDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailsFragment : DetailsSupportFragment() {

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var detailsRow: DetailsOverviewRow
    private val detailActions = ArrayObjectAdapter()
    private var titleText: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var tvDetails: TvDetails? = null
    private var resumeSeconds: Long = 0L
    private var resumeSeason: Int = 1
    private var resumeEpisode: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tmdbId = requireActivity().intent.getIntExtra(DetailsActivity.EXTRA_TMDB_ID, -1)
        mediaType = requireActivity().intent.getStringExtra(DetailsActivity.EXTRA_MEDIA_TYPE) ?: "movie"

        val presenterSelector = ClassPresenterSelector()
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(DetailDescriptionPresenter())
        detailsPresenter.backgroundColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
        detailsPresenter.actionsBackgroundColor = ContextCompat.getColor(requireContext(), R.color.background_card)
        detailsPresenter.onActionClickedListener = OnActionClickedListener { action ->
            when (action.id) {
                ACTION_PLAY -> launchPlayer(resumeSeconds = 0L, season = 1, episode = 1)
                ACTION_RESUME -> launchPlayer(resumeSeconds = resumeSeconds, season = resumeSeason, episode = resumeEpisode)
            }
        }
        presenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
        presenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())
        rowsAdapter = ArrayObjectAdapter(presenterSelector)

        detailsRow = DetailsOverviewRow(PlaceholderInfo("", ""))
        detailsRow.actionsAdapter = detailActions
        rowsAdapter.add(detailsRow)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                launchPlayer(resumeSeconds = 0L, season = item.seasonNumber, episode = item.episodeNumber)
            }
        }

        loadDetails()
    }

    private fun launchPlayer(resumeSeconds: Long, season: Int, episode: Int) {
        val intent = PlaybackLauncherActivity.intent(
            requireContext(),
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = titleText,
            posterPath = posterPath,
            backdropPath = backdropPath,
            season = if (mediaType == "tv") season else 0,
            episode = if (mediaType == "tv") episode else 0,
            resumeSeconds = resumeSeconds
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshResumeAction()
    }

    private fun refreshResumeAction() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(requireContext()).watchProgressDao()
            val record: WatchProgress? = withContext(Dispatchers.IO) {
                if (mediaType == "movie") dao.get(WatchProgress.keyFor(tmdbId, "movie"))
                else null
            }

            detailActions.clear()
            if (mediaType == "movie") {
                if (record != null && !record.finished && record.currentTime > 30) {
                    resumeSeconds = record.currentTime.toLong()
                    detailActions.add(
                        Action(ACTION_RESUME, getString(R.string.action_resume),
                            formatTime(record.currentTime))
                    )
                }
                detailActions.add(Action(ACTION_PLAY, getString(R.string.action_play)))
            } else {
                detailActions.add(Action(ACTION_PLAY, getString(R.string.action_play), "S1E1"))
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val s = seconds.toLong()
        val m = s / 60
        val h = m / 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m % 60, s % 60)
        else String.format("%d:%02d", m, s % 60)
    }

    private fun loadDetails() {
        lifecycleScope.launch {
            runCatching {
                if (mediaType == "movie") {
                    val d = Tmdb.api.movieDetails(tmdbId, Tmdb.API_KEY)
                    bindMovie(d)
                } else {
                    val d = Tmdb.api.tvDetails(tmdbId, Tmdb.API_KEY)
                    tvDetails = d
                    bindTv(d)
                    loadFirstSeason(d)
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    private fun bindMovie(d: MovieDetails) {
        titleText = d.title ?: "Untitled"
        posterPath = d.posterPath
        backdropPath = d.backdropPath
        val info = PlaceholderInfo(
            title = titleText,
            body = listOfNotNull(
                d.releaseDate?.take(4),
                d.runtime?.takeIf { it > 0 }?.let { "$it min" },
                d.voteAverage?.takeIf { it > 0 }?.let { String.format("★ %.1f", it) },
                d.genres.mapNotNull { it.name }.joinToString(", ").takeIf { it.isNotBlank() }
            ).joinToString("  •  ") + "\n\n" + (d.overview ?: "")
        )
        detailsRow.item = info
        loadPoster(Tmdb.posterUrl(d.posterPath))
        rowsAdapter.notifyArrayItemRangeChanged(0, 1)
        refreshResumeAction()
    }

    private fun bindTv(d: TvDetails) {
        titleText = d.name ?: "Untitled"
        posterPath = d.posterPath
        backdropPath = d.backdropPath
        val info = PlaceholderInfo(
            title = titleText,
            body = listOfNotNull(
                d.firstAirDate?.take(4),
                d.numberOfSeasons?.takeIf { it > 0 }?.let { "$it seasons" },
                d.voteAverage?.takeIf { it > 0 }?.let { String.format("★ %.1f", it) },
                d.genres.mapNotNull { it.name }.joinToString(", ").takeIf { it.isNotBlank() }
            ).joinToString("  •  ") + "\n\n" + (d.overview ?: "")
        )
        detailsRow.item = info
        loadPoster(Tmdb.posterUrl(d.posterPath))
        rowsAdapter.notifyArrayItemRangeChanged(0, 1)
    }

    private fun loadPoster(url: String?) {
        if (url == null) return
        Glide.with(this).asBitmap().load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    detailsRow.setImageBitmap(requireContext(), resource)
                    rowsAdapter.notifyArrayItemRangeChanged(0, 1)
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun loadFirstSeason(d: TvDetails) {
        val seasons = d.seasons.filter { it.seasonNumber > 0 }
        if (seasons.isEmpty()) return
        lifecycleScope.launch {
            seasons.take(3).forEach { season ->
                runCatching {
                    val sd = Tmdb.api.tvSeason(d.id, season.seasonNumber, Tmdb.API_KEY)
                    val adapter = ArrayObjectAdapter(EpisodePresenter())
                    sd.episodes.forEach { adapter.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(season.seasonNumber.toLong(),
                        season.name ?: "Season ${season.seasonNumber}"), adapter))
                }
            }
        }
    }

    data class PlaceholderInfo(val title: String, val body: String)

    companion object {
        const val ACTION_PLAY = 1L
        const val ACTION_RESUME = 2L
    }
}
