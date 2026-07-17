package dev.sparkynox.sparkytube

import android.app.Application

class SparkyTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nothing heavy here on purpose — AdBlockEngine loads its JSON lazily
        // the first time MainActivity spins up the WebView.
    }
}
