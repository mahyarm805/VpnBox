package com.vpnbox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WhiteHoleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
