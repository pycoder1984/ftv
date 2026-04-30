package com.vidking.firetv.presenters

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.vidking.firetv.R
import com.vidking.firetv.db.WatchProgress
import com.vidking.firetv.tmdb.MediaItem
import com.vidking.firetv.tmdb.Tmdb

class CardPresenter : Presenter() {
    private var defaultBg: Drawable? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx: Context = parent.context
        defaultBg = ContextCompat.getDrawable(ctx, R.drawable.card_default)
        val card = object : ImageCardView(ctx) {
            override fun setSelected(selected: Boolean) {
                val color = ContextCompat.getColor(
                    ctx,
                    if (selected) R.color.selected_background else R.color.background_card
                )
                findViewById<android.view.View>(androidx.leanback.R.id.info_field)?.setBackgroundColor(color)
                super.setSelected(selected)
            }
        }
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageDimensions(CARD_W, CARD_H)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = viewHolder.view as ImageCardView
        when (item) {
            is MediaItem -> {
                card.titleText = item.displayTitle
                card.contentText = if (item.isMovie()) "Movie • ${item.displayDate}" else "TV • ${item.displayDate}"
                Glide.with(card.context)
                    .load(Tmdb.posterUrl(item.posterPath))
                    .placeholder(defaultBg)
                    .centerCrop()
                    .into(card.mainImageView)
            }
            is WatchProgress -> {
                card.titleText = item.title
                val pct = (item.progressPct * 100).toInt().coerceIn(0, 100)
                card.contentText = if (item.mediaType == "tv")
                    "S${item.season}E${item.episode} • $pct%"
                else "Movie • $pct%"
                Glide.with(card.context)
                    .load(Tmdb.posterUrl(item.posterPath))
                    .placeholder(defaultBg)
                    .centerCrop()
                    .into(card.mainImageView)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.badgeImage = null
        card.mainImage = null
    }

    companion object {
        private const val CARD_W = 313
        private const val CARD_H = 470
    }
}
