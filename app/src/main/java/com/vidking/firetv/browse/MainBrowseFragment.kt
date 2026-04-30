package com.vidking.firetv.browse

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.R
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import com.vidking.firetv.details.DetailsActivity
import com.vidking.firetv.player.PlayerActivity
import com.vidking.firetv.presenters.CardPresenter
import com.vidking.firetv.search.SearchActivity
import com.vidking.firetv.tmdb.MediaItem
import com.vidking.firetv.tmdb.Tmdb
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainBrowseFragment : BrowseSupportFragment() {
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var continueRow: ListRow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_red)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_red)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is MediaItem -> {
                    val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                        putExtra(DetailsActivity.EXTRA_TMDB_ID, item.id)
                        putExtra(DetailsActivity.EXTRA_MEDIA_TYPE, if (item.isMovie()) "movie" else "tv")
                    }
                    startActivity(intent)
                }
                is WatchProgress -> startPlayer(item)
            }
        }

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter
        loadRows()
    }

    private fun startPlayer(p: WatchProgress) {
        val intent = PlayerActivity.intent(
            requireContext(),
            tmdbId = p.tmdbId,
            mediaType = p.mediaType,
            title = p.title,
            posterPath = p.posterPath,
            backdropPath = p.backdropPath,
            season = p.season,
            episode = p.episode,
            resumeSeconds = p.currentTime.toLong()
        )
        startActivity(intent)
    }

    private fun loadRows() {
        val cardPresenter = CardPresenter()
        val continueAdapter = ArrayObjectAdapter(cardPresenter)
        val trendingAdapter = ArrayObjectAdapter(cardPresenter)
        val moviesAdapter = ArrayObjectAdapter(cardPresenter)
        val tvAdapter = ArrayObjectAdapter(cardPresenter)

        // Continue watching row (placeholder, reactive)
        continueRow = ListRow(HeaderItem(0, getString(R.string.header_continue)), continueAdapter)
        rowsAdapter.add(ListRow(HeaderItem(1, getString(R.string.header_trending)), trendingAdapter))
        rowsAdapter.add(ListRow(HeaderItem(2, getString(R.string.header_popular_movies)), moviesAdapter))
        rowsAdapter.add(ListRow(HeaderItem(3, getString(R.string.header_popular_tv)), tvAdapter))

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                Tmdb.api.trending(Tmdb.API_KEY).results.forEach { trendingAdapter.add(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                Tmdb.api.popularMovies(Tmdb.API_KEY).results.forEach { moviesAdapter.add(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                Tmdb.api.popularTv(Tmdb.API_KEY).results.forEach { tvAdapter.add(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(requireContext()).watchProgressDao().continueWatching()
                .collectLatest { items ->
                    continueAdapter.clear()
                    items.forEach { continueAdapter.add(it) }
                    val hasRow = rowsAdapter.indexOf(continueRow) >= 0
                    if (items.isNotEmpty() && !hasRow) {
                        rowsAdapter.add(0, continueRow!!)
                    } else if (items.isEmpty() && hasRow) {
                        rowsAdapter.remove(continueRow)
                    }
                }
        }
    }
}
