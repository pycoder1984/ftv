package com.vidking.firetv

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showLastCrashIfAny()
    }

    private fun showLastCrashIfAny() {
        val prefs = getSharedPreferences(App.CRASH_PREF, MODE_PRIVATE)
        val crash = prefs.getString(App.KEY_LAST_CRASH, null) ?: return
        prefs.edit().remove(App.KEY_LAST_CRASH).apply()
        AlertDialog.Builder(this)
            .setTitle("Last crash (tap OK to dismiss)")
            .setMessage(crash.take(4000))
            .setPositiveButton("OK", null)
            .show()
    }
}
