package com.vidking.firetv.details

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.vidking.firetv.R

class DetailsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
    }
    companion object {
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_MEDIA_TYPE = "media_type"
    }
}
