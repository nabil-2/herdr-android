package dev.herdr.android

import android.app.Application
import dev.herdr.android.data.CrashDiagnostics

class HerdrPocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}
