package com.vidking.firetv.player

/**
 * Embed providers fed into the hidden-WebView sniffer.
 *
 * On-device verified 2026-05-11 against Fire TV (Project Hail Mary, TMDB
 * 687163). The three providers below all reach `cloudnestra.com` / use
 * `disable-devtool`-style antibot that detects WebView and refuses to emit
 * the stream URL:
 *
 *   - vidsrc.to    (chain: vidsrc.to → vsembed.ru → cloudnestra.com)
 *   - vidsrc.me    (chain: vidsrc.me → vidsrcme.ru → cloudnestra.com)
 *   - 2embed.skin  (disable-devtool kills the player before iframe init)
 *
 * They were removed rather than left in the picker because each one burned
 * 24 s of timeout before failing. Re-add only if a future build can defeat
 * the antibot (navigator.webdriver / WebGL fingerprint spoof). Earlier
 * removed dead providers: vidsrc.dev, multiembed.mov, nontongo.win,
 * rivestream.live, streamingnow.mov, 2embed.cc, autoembed.cc, vidking.
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
            id = "aether",
            name = "Aether",
            baseUrl = "https://aether.mom"
        ) { id, type, s, e, _ ->
            if (type == "movie") "https://aether.mom/media/tmdb-movie-$id"
            else "https://aether.mom/media/tmdb-tv-$id/$s/$e"
        }
    )
}
