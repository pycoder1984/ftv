package com.vidking.firetv.data

import android.content.Context
import android.content.SharedPreferences
import com.vidking.firetv.BuildConfig

/**
 * SharedPreferences-backed store for user settings (Febbox endpoint + token)
 * and crash diagnostics.
 *
 * The Febbox endpoint URL and siteadmin token fall back to compile-time
 * defaults from [BuildConfig] when the user hasn't explicitly set them. This
 * means a fresh sideload of the GitHub-release APK works out of the box —
 * the source picker lists Febbox immediately, no manual config step.
 *
 * Users can still override the defaults via Settings; "Clear Febbox settings"
 * wipes the user overrides, which reverts both fields to the baked-in
 * defaults.
 */
object AppPrefs {

    private const val PREFS_NAME = "vidking_prefs"

    private const val KEY_FEBBOX_BASE_URL = "febbox_base_url"
    private const val KEY_FEBBOX_TOKEN = "febbox_token"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Effective Febbox endpoint: user override if set, else compile-time default. */
    fun febboxBaseUrl(context: Context): String {
        val user = prefs(context).getString(KEY_FEBBOX_BASE_URL, "").orEmpty().trim()
        return if (user.isNotEmpty()) user else BuildConfig.FEBBOX_DEFAULT_BASE_URL.trim()
    }

    fun setFebboxBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_FEBBOX_BASE_URL, normalized).apply()
    }

    /** Effective Febbox token: user override if set, else compile-time default. */
    fun febboxToken(context: Context): String {
        val user = prefs(context).getString(KEY_FEBBOX_TOKEN, "").orEmpty().trim()
        return if (user.isNotEmpty()) user else BuildConfig.FEBBOX_DEFAULT_TOKEN.trim()
    }

    fun setFebboxToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FEBBOX_TOKEN, value.trim()).apply()
    }

    /** True when the effective config is non-empty — almost always true now that defaults exist. */
    fun hasFebboxConfig(context: Context): Boolean =
        febboxBaseUrl(context).isNotEmpty() && febboxToken(context).isNotEmpty()

    /** Has the user supplied a custom URL distinct from the baked-in default? */
    fun hasUserFebboxBaseUrl(context: Context): Boolean =
        prefs(context).getString(KEY_FEBBOX_BASE_URL, "").orEmpty().trim().isNotEmpty()

    /** Has the user supplied a custom token distinct from the baked-in default? */
    fun hasUserFebboxToken(context: Context): Boolean =
        prefs(context).getString(KEY_FEBBOX_TOKEN, "").orEmpty().trim().isNotEmpty()

    fun clearFebboxOverrides(context: Context) {
        prefs(context).edit()
            .remove(KEY_FEBBOX_BASE_URL)
            .remove(KEY_FEBBOX_TOKEN)
            .apply()
    }

    fun lastCrash(context: Context): String? =
        prefs(context).getString(KEY_LAST_CRASH, null)

    fun lastCrashAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CRASH_AT, 0L)

    fun saveCrash(context: Context, payload: String) {
        prefs(context).edit()
            .putString(KEY_LAST_CRASH, payload)
            .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
            .commit()
    }

    fun clearCrash(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_CRASH)
            .remove(KEY_LAST_CRASH_AT)
            .apply()
    }
}
