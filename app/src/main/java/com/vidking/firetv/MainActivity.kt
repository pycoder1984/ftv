package com.vidking.firetv

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.vidking.firetv.data.AppPrefs

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showLastCrashIfAny()
    }

    private fun showLastCrashIfAny() {
        val crash = AppPrefs.lastCrash(this) ?: return
        AppPrefs.clearCrash(this)
        AlertDialog.Builder(this)
            .setTitle("Last crash (tap OK to dismiss)")
            .setMessage(crash.take(4000))
            .setPositiveButton("OK", null)
            .show()
    }
}
