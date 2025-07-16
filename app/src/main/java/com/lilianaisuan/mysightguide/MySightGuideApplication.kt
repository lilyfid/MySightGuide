package com.lilianaisuan.mysightguide

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MySightGuideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}