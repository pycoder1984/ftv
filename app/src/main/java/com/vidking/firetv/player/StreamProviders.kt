package com.vidking.firetv.player

/**
 * Embed providers loaded full-screen as the actual player. The user interacts
 * with the iframe directly via D-Pad — no scraping, no sniffing.
 *
 * Live as of 2026-05-01 (probed via curl). Dead providers (vidsrc.dev,
 * multiembed.mov, nontongo.win, rivestream.live, streamingnow.mov, 2embed.cc,
 * autoembed.cc) removed. Vidking removed at user request after it kept
 * returning empty source lists for non-blockbuster titles.
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
            id = "vidsrc-to",
            name = "VidSrc.to",
            baseUrl = "https://vidsrc.to"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://vidsrc.to/embed/movie/$id"
            else "https://vidsrc.to/embed/tv/$id/$s/$e"
        },

        StreamProvider(
            id = "moviesapi",
            name = "MoviesAPI",
            baseUrl = "https://moviesapi.club"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://moviesapi.club/movie/$id"
            else "https://moviesapi.club/tv/$id-$s-$e"
        },

        StreamProvider(
            id = "vidlink",
            name = "VidLink",
            baseUrl = "https://vidlink.pro"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        },

        StreamProvider(
            id = "vidsrc-me",
            name = "VidSrc.me",
            baseUrl = "https://vidsrc.me"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://vidsrc.me/embed/movie?tmdb=$id"
            else "https://vidsrc.me/embed/tv?tmdb=$id&season=$s&episode=$e"
        },

        StreamProvider(
            id = "2embed-skin",
            name = "2Embed.skin",
            baseUrl = "https://2embed.skin"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://2embed.skin/embed/movie?tmdb=$id"
            else "https://2embed.skin/embed/tv?tmdb=$id&season=$s&episode=$e"
        }
    )
}
