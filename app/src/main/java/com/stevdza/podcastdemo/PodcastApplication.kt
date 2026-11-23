package com.stevdza.podcastdemo

import android.app.Application
import com.stevdza.podcastdemo.di.podcastModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PodcastApplication : Application() {
    private val checkpointPersistence: CheckpointPersistence by inject()

    override fun onCreate() {
        super.onCreate()
        initializeKoin()
        checkpointPersistence.startSync()
    }
}

private fun Application.initializeKoin() {
    startKoin {
        androidContext(this@initializeKoin.applicationContext)
        modules(podcastModule)
    }
}
