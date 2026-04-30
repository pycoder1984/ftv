package com.vidking.firetv

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val payload = "Thread: ${thread.name}\n\n$sw"
                getSharedPreferences(CRASH_PREF, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CRASH, payload)
                    .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
                    .commit()
            } catch (_: Throwable) {
                // never let the crash handler itself crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_PREF = "crash"
        const val KEY_LAST_CRASH = "last_crash"
        const val KEY_LAST_CRASH_AT = "last_crash_at"
    }
}
