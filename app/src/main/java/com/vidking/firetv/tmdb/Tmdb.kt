package com.vidking.firetv.tmdb

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vidking.firetv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object Tmdb {
    const val API_KEY = BuildConfig.TMDB_API_KEY
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

    fun posterUrl(path: String?, size: String = "w342"): String? =
        if (path.isNullOrBlank()) null else "$IMAGE_BASE$size$path"

    fun backdropUrl(path: String?, size: String = "w1280"): String? =
        if (path.isNullOrBlank()) null else "$IMAGE_BASE$size$path"

    val api: TmdbApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            })
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApi::class.java)
    }
}
