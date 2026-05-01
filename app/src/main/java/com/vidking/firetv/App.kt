package com.vidking.firetv

import android.app.Application
import com.vidking.firetv.data.AppPrefs
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
                AppPrefs.saveCrash(this, "Thread: ${thread.name}\n\n$sw")
            } catch (_: Throwable) {
                // never let the crash handler itself crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
