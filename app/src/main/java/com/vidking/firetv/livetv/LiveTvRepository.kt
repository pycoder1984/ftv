package com.vidking.firetv.livetv

import android.content.Context
import android.util.Log
import com.vidking.firetv.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LiveTvRepository {

    private const val TAG = "LiveTvRepository"

    /**
     * Curated free live channels shown when no user playlist is configured.
     * All are official broadcaster streams on public CDNs. URLs can change —
     * the user can override the entire list via Settings → IPTV Playlist URL.
     */
    val BUILTIN_CHANNELS: List<Channel> = listOf(
        Channel(
            id = "al-jazeera-en",
            name = "Al Jazeera English",
            logoUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/f/f2/Al_Jazeera_English_logo.svg/250px-Al_Jazeera_English_logo.svg.png",
            streamUrl = "https://live-hls-web-aje.getaj.net/AJE/index.m3u8",
            group = "News"
        ),
        Channel(
            id = "dw-en",
            name = "DW English",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/75/DW_Fernsehen_%28Logo%29.svg/250px-DW_Fernsehen_%28Logo%29.svg.png",
            streamUrl = "https://dwamdstream102.akamaized.net/hls/live/2015529/dwstream102/index.m3u8",
            group = "News"
        ),
        Channel(
            id = "france24-en",
            name = "France 24 English",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a0/France_24_logo.svg/250px-France_24_logo.svg.png",
            streamUrl = "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8",
            group = "News"
        ),
        Channel(
            id = "euronews-en",
            name = "Euronews English",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5e/Euronews_logo_2022.svg/250px-Euronews_logo_2022.svg.png",
            streamUrl = "https://euronews-euronews-en-live.cast.addradio.de/euronews/euronewsen/live/hls/index.m3u8",
            group = "News"
        ),
        Channel(
            id = "nhk-world",
            name = "NHK World Japan",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/NHK_World_logo.svg/250px-NHK_World_logo.svg.png",
            streamUrl = "https://nhkwlive-xjp.akamaized.net/hls/live/2003459/nhkwlive-xjp-en/index_1M.m3u8",
            group = "News"
        ),
        Channel(
            id = "cgtn-en",
            name = "CGTN English",
            logoUrl = null,
            streamUrl = "https://news.cgtn.com/resource/live/english/cgtn-news.m3u8",
            group = "News"
        ),
        Channel(
            id = "nasa-tv",
            name = "NASA TV",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/NASA_logo.svg/250px-NASA_logo.svg.png",
            streamUrl = "https://ntv3.nasa.gov/NTV_Public3@Microsoft/master.m3u8",
            group = "Science"
        ),
        Channel(
            id = "bloomberg-tv",
            name = "Bloomberg TV",
            logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5d/Bloomberg_logo.svg/250px-Bloomberg_logo.svg.png",
            streamUrl = "https://cdn-news.bamgrid.com/media/3734802/bloomberg_us_2.m3u8",
            group = "Business"
        ),
        Channel(
            id = "sky-news",
            name = "Sky News",
            logoUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/8/84/Sky_News_logo_2020.svg/250px-Sky_News_logo_2020.svg.png",
            streamUrl = "https://linear227.sky.com/out/v1/518dfd14cf7f43d7b6069b4fc6ce7b87/index.m3u8",
            group = "News"
        ),
        Channel(
            id = "abc-australia",
            name = "ABC News Australia",
            logoUrl = null,
            streamUrl = "https://abcnewslive.hls.abc.net.au/p/abc1/abcnewslive-7lus7yrs0s-g7v8rnqmwrv.m3u8",
            group = "News"
        ),
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun loadChannels(context: Context): List<Channel> {
        val userUrl = AppPrefs.livetvPlaylistUrl(context)
        if (userUrl.isBlank()) return BUILTIN_CHANNELS
        return try {
            fetchPlaylist(userUrl)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load user playlist from $userUrl: ${t.message}")
            BUILTIN_CHANNELS
        }
    }

    private suspend fun fetchPlaylist(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // IPTV providers often whitelist VLC
            .header("User-Agent", "VLC/3.0 LibVLC/3.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            M3uParser.parse(response.body?.string() ?: "")
        }
    }
}
