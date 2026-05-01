package com.vidking.firetv.player

/**
 * Embed providers we cycle through to find a working stream. Each one is loaded
 * inside a hidden WebView; we then sniff the m3u8 from network requests and hand
 * it off to ExoPlayer for native playback.
 *
 * Mirrors the 6 providers used by web/index.html.
 */
data class StreamProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val builder: (tmdbId: Int, mediaType: String, season: Int, episode: Int, resumeSeconds: Long) -> String
)

object StreamProviders {

    val ALL: List<StreamProvider> = listOf(
        StreamProvider(
            id = "vidking",
            name = "Vidking",
            baseUrl = "https://www.vidking.net"
        ) { id, type, s, e, resume ->
            val params = buildList {
                add("color=e50914")
                add("autoPlay=true")
                if (type == "tv") {
                    add("nextEpisode=true")
                    add("episodeSelector=true")
                }
                if (resume > 0) add("progress=$resume")
            }.joinToString("&")
            if (type == "movie") "https://www.vidking.net/embed/movie/$id?$params"
            else "https://www.vidking.net/embed/tv/$id/$s/$e?$params"
        },

        StreamProvider(
            id = "vidsrc-cc",
            name = "VidSrc.cc",
            baseUrl = "https://vidsrc.cc"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://vidsrc.cc/v2/embed/movie/$id?autoPlay=true"
            else "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?autoPlay=true"
        },

        StreamProvider(
            id = "vidsrc-xyz",
            name = "VidSrc.xyz",
            baseUrl = "https://vidsrc.xyz"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://vidsrc.xyz/embed/movie?tmdb=$id"
            else "https://vidsrc.xyz/embed/tv?tmdb=$id&season=$s&episode=$e"
        },

        StreamProvider(
            id = "embedsu",
            name = "Embed.su",
            baseUrl = "https://embed.su"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://embed.su/embed/movie/$id"
            else "https://embed.su/embed/tv/$id/$s/$e"
        },

        StreamProvider(
            id = "2embed",
            name = "2Embed",
            baseUrl = "https://www.2embed.cc"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://www.2embed.cc/embed/$id"
            else "https://www.2embed.cc/embedtv/$id&s=$s&e=$e"
        },

        StreamProvider(
            id = "autoembed",
            name = "AutoEmbed",
            baseUrl = "https://player.autoembed.cc"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://player.autoembed.cc/embed/movie/$id"
            else "https://player.autoembed.cc/embed/tv/$id/$s/$e"
        }
    )
}
