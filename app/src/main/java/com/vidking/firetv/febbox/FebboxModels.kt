package com.vidking.firetv.febbox

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Loose response shape modelled on github.com/zainulnazir/showbox-febbox-api.
 * All fields nullable so the client tolerates upstream drift; the Repository
 * picks the best available source and skips malformed entries.
 */
@JsonClass(generateAdapter = false)
data class FebboxResolveResponse(
    val sources: List<FebboxSource>? = null,
    val subtitles: List<FebboxSubtitle>? = null,
    val intro: FebboxIntroMarker? = null,
    val referer: String? = null
)

@JsonClass(generateAdapter = false)
data class FebboxSource(
    val url: String? = null,
    val quality: String? = null,
    val type: String? = null,
    val server: String? = null,
    val size: Long? = null
) {
    /** Coarse rank used to pick the highest-quality entry. */
    fun qualityRank(): Int {
        val q = quality?.lowercase() ?: return 0
        return when {
            "2160" in q || "4k" in q || "uhd" in q -> 4000
            "1440" in q || "2k" in q -> 2000
            "1080" in q || "fhd" in q -> 1080
            "720" in q || "hd" in q -> 720
            "480" in q -> 480
            "360" in q -> 360
            else -> 0
        }
    }
}

@JsonClass(generateAdapter = false)
data class FebboxSubtitle(
    @Json(name = "lang") val lang: String? = null,
    @Json(name = "language") val languageAlt: String? = null,
    val url: String? = null,
    val format: String? = null,
    val display: String? = null
) {
    fun bestLabel(): String =
        display?.takeIf { it.isNotBlank() }
            ?: lang?.takeIf { it.isNotBlank() }
            ?: languageAlt?.takeIf { it.isNotBlank() }
            ?: "Subtitle"

    fun bestLang(): String =
        lang?.takeIf { it.isNotBlank() }
            ?: languageAlt?.takeIf { it.isNotBlank() }
            ?: "und"
}

@JsonClass(generateAdapter = false)
data class FebboxIntroMarker(
    val start: Double? = null,
    val end: Double? = null
)
