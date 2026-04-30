package com.vidking.firetv.details

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter

class DetailDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(viewHolder: ViewHolder, item: Any?) {
        val info = item as? DetailsFragment.PlaceholderInfo ?: return
        viewHolder.title.text = info.title
        viewHolder.body.text = info.body
        viewHolder.subtitle.text = ""
    }
}
