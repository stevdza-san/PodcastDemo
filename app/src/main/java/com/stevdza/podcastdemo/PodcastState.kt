package com.stevdza.podcastdemo

import androidx.appstate.datastore.PersistToDataStore
import androidx.appstate.statestore.StateStore
import androidx.appstate.statestore.StateStoreKey
import androidx.compose.runtime.State
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

enum class PlaybackStatus { Preparing, Ready, Playing, Paused, Stopped, Error }

// Live player state belongs to this process; it must not be restored as "Playing".
private object PlaybackStatusKey : StateStoreKey<PlaybackStatus>(defaultValue = PlaybackStatus.Preparing)

private object LastPausePositionKey : StateStoreKey<Long?>(
    defaultValue = null,
    autoClearKey = PlaybackStatusKey,
    shouldClearState = { store ->
        store.getState(stateKey = PlaybackStatusKey).value == PlaybackStatus.Stopped
    },
)

private object ResumePositionKey : StateStoreKey<Long?>(defaultValue = null), PersistToDataStore<Long?> {
    override val name = "resume_position_ms"
    override val serializer: KSerializer<Long?> = Long.serializer().nullable
}

class PodcastState(
    private val checkpointStore: StateStore,
    private val sessionStore: StateStore = StateStore(),
) {
    // Keep the keys observed by syncToDataStore stable. Adding or auto-clearing a session
    // key in that store can restart alpha03's restore effect and replay an older disk value.
    val playbackStatus: State<PlaybackStatus> = sessionStore.getState(stateKey = PlaybackStatusKey)
    val resumePositionMs: State<Long?> = checkpointStore.getState(stateKey = ResumePositionKey)
    val lastPausePositionMs: Long?
        get() = if (LastPausePositionKey in sessionStore.keys) {
            sessionStore.getState(LastPausePositionKey).value
        } else {
            null
        }

    fun savePausePosition(positionMs: Long) {
        setPlaybackStatus(PlaybackStatus.Paused)
        val position = positionMs.coerceAtLeast(0L)
        sessionStore.setState(stateKey = LastPausePositionKey, value = position)
        saveResumePosition(positionMs = position)
    }

    fun stopAndReset() {
        setPlaybackStatus(PlaybackStatus.Stopped)
        // Here we don't need to explicitly reset the LastPausePositionKey
        saveResumePosition(0L)
    }

    fun setPlaybackStatus(status: PlaybackStatus) {
        sessionStore.setState(stateKey = PlaybackStatusKey, value = status)
    }

    fun saveResumePosition(positionMs: Long) {
        checkpointStore.setState(stateKey = ResumePositionKey, value = positionMs.coerceAtLeast(0L))
    }
}
