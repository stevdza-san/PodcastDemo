package com.stevdza.podcastdemo

import android.app.Application
import android.util.Log
import androidx.appstate.datastore.syncToDataStore
import androidx.appstate.statestore.StateStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PodcastApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = StateStore()

    // getState registers both keys before the persistence listener starts.
    val podcast = PodcastState(store)
    var hasCheckpointFileAtLaunch = false
        private set

    override fun onCreate() {
        super.onCreate()
        val file = File(filesDir, "podcast-resume.json")
        hasCheckpointFileAtLaunch = file.exists()
        scope.launch {
            try {
                store.syncToDataStore(path = file.absolutePath, coroutineScope = scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A checkpoint on screen updates before the asynchronous disk write finishes.
                Log.e("PodcastDemo", "Checkpoint sync failed; resume verification failed", error)
            }
        }
    }
}
