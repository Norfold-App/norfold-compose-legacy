package com.norfold.app

import android.app.Application
import com.norfold.app.data.DiagnosticsStore

class NorfoldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.install(this)
    }
}
