package com.claude.musicplayer

import android.app.Application

class MusicPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
