package com.stevdza.podcastdemo

import android.content.Context
import android.util.Log
import androidx.appstate.datastore.syncToDataStore
import androidx.appstate.statestore.StateStore
import androidx.compose.runtime.snapshotFlow
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Owns the checkpoint file and waits for restoration before the player prepares. */
class CheckpointPersistence(
    context: Context,
    private val store: StateStore,
    private val podcast: PodcastState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val file = File(context.filesDir, "podcast-resume.json")
    val hasCheckpointFileAtLaunch: Boolean = file.exists()

    fun startSync() {
        // PodcastState registers the persisted key before the sync listener starts.
        scope.launch {
            try {
                store.syncToDataStore(path = file.absolutePath, coroutineScope = scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("PodcastDemo", "Checkpoint sync failed; resume verification failed", error)
            }
        }
    }

    suspend fun positionForPreparation(): Long = if (hasCheckpointFileAtLaunch) {
        checkNotNull(withTimeoutOrNull(15_000.milliseconds) {
            snapshotFlow { podcast.resumePositionMs.value }.filterNotNull().first()
        }) { "Checkpoint was not restored within 15 seconds" }
    } else {
        podcast.resumePositionMs.value ?: 0L
    }
}
