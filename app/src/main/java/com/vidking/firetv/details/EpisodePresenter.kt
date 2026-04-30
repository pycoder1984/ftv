package com.vidking.firetv.details

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.vidking.firetv.R
import com.vidking.firetv.tmdb.Episode
import com.vidking.firetv.tmdb.Tmdb

class EpisodePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageDimensions(CARD_W, CARD_H)
        card.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.background_card))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val ep = item as Episode
        val card = viewHolder.view as ImageCardView
        card.titleText = "E${ep.episodeNumber} • ${ep.name ?: ""}"
        card.contentText = ep.airDate ?: ""
        Glide.with(card.context)
            .load(Tmdb.backdropUrl(ep.stillPath, "w300"))
            .placeholder(R.drawable.card_default)
            .centerCrop()
            .into(card.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.mainImage = null
    }

    companion object {
        private const val CARD_W = 480
        private const val CARD_H = 270
    }
}
