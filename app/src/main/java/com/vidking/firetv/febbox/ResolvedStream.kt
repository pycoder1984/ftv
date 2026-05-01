package com.vidking.firetv.febbox

import android.os.Parcel
import android.os.Parcelable

/**
 * Output of FebboxRepository.resolve() — everything ExoPlayerActivity needs to
 * start playback without the WebView sniffer. Subtitles use the same shape
 * regardless of whether they came from Febbox or from the Wyzie fallback.
 */
data class ResolvedStream(
    val url: String,
    val referer: String,
    val userAgent: String,
    val subtitles: List<SubtitleTrack>,
    val introStartMs: Long,
    val introEndMs: Long
) {
    val hasIntroMarker: Boolean
        get() = introStartMs >= 0L && introEndMs > introStartMs
}

data class SubtitleTrack(
    val url: String,
    val label: String,
    val language: String,
    val mimeType: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(url)
        dest.writeString(label)
        dest.writeString(language)
        dest.writeString(mimeType)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SubtitleTrack> {
            override fun createFromParcel(parcel: Parcel) = SubtitleTrack(parcel)
            override fun newArray(size: Int) = arrayOfNulls<SubtitleTrack>(size)
        }

        fun mimeFromUrlOrFormat(url: String, format: String?): String {
            val lower = (format ?: url).lowercase()
            return when {
                "vtt" in lower -> "text/vtt"
                "ass" in lower || "ssa" in lower -> "text/x-ssa"
                else -> "application/x-subrip"
            }
        }
    }
}
