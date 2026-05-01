package com.vidking.firetv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Single SharedPreferences-backed store for user settings (Febbox token, base URL,
 * future preferences) and crash diagnostics. Wraps a private prefs file named
 * "vidking_prefs". All reads/writes are synchronous; callers should not invoke
 * from the UI critical path during scrolling.
 */
object AppPrefs {

    private const val PREFS_NAME = "vidking_prefs"

    private const val KEY_FEBBOX_BASE_URL = "febbox_base_url"
    private const val KEY_FEBBOX_TOKEN = "febbox_token"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun febboxBaseUrl(context: Context): String =
        prefs(context).getString(KEY_FEBBOX_BASE_URL, "").orEmpty().trim()

    fun setFebboxBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_FEBBOX_BASE_URL, normalized).apply()
    }

    fun febboxToken(context: Context): String =
        prefs(context).getString(KEY_FEBBOX_TOKEN, "").orEmpty().trim()

    fun setFebboxToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FEBBOX_TOKEN, value.trim()).apply()
    }

    fun hasFebboxConfig(context: Context): Boolean =
        febboxBaseUrl(context).isNotEmpty() && febboxToken(context).isNotEmpty()

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
