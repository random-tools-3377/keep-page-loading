package io.github.randomtools3377.keeppageloading

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Tabs.app = this
    }
}
