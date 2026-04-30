package com.vidking.firetv.search

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.details.DetailsActivity
import com.vidking.firetv.presenters.CardPresenter
import com.vidking.firetv.tmdb.MediaItem
import com.vidking.firetv.tmdb.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var pendingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                    putExtra(DetailsActivity.EXTRA_TMDB_ID, item.id)
                    putExtra(DetailsActivity.EXTRA_MEDIA_TYPE, if (item.isMovie()) "movie" else "tv")
                }
                startActivity(intent)
            }
        })
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        scheduleSearch(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        scheduleSearch(query, immediate = true)
        return true
    }

    private fun scheduleSearch(query: String?, immediate: Boolean = false) {
        pendingJob?.cancel()
        rowsAdapter.clear()
        val q = query?.trim().orEmpty()
        if (q.length < 2) return
        pendingJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(300)
            runCatching {
                val results = Tmdb.api.searchMulti(Tmdb.API_KEY, q).results
                    .filter { it.posterPath != null && (it.mediaType == "movie" || it.mediaType == "tv") }
                val adapter = ArrayObjectAdapter(CardPresenter())
                results.forEach { adapter.add(it) }
                rowsAdapter.clear()
                rowsAdapter.add(ListRow(HeaderItem(0, "Results for \"$q\""), adapter))
            }
        }
    }
}
