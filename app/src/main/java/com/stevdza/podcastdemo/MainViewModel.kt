package com.stevdza.podcastdemo

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.stevdza.podcastdemo.service.PlaybackService
import com.stevdza.podcastdemo.service.StopAndResetCommand

internal data class PodcastUiState(
    val status: PlaybackStatus,
    val savedPositionMs: Long?,
    val lastPausePositionMs: Long?,
    val restoring: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canStop: Boolean,
    val connectionFailed: Boolean,
)

internal class MainViewModel(
    private val appContext: Context,
    private val podcast: PodcastState,
    private val checkpoint: CheckpointPersistence,
) : ViewModel() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var connectionFailed by mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val uiState: PodcastUiState
        get() {
            val status = podcast.playbackStatus.value
            val savedPositionMs = podcast.resumePositionMs.value
            val lastPausePositionMs = podcast.lastPausePositionMs
            val connected = controller != null
            return PodcastUiState(
                status = status,
                savedPositionMs = savedPositionMs,
                lastPausePositionMs = lastPausePositionMs,
                restoring = checkpoint.hasCheckpointFileAtLaunch && savedPositionMs == null,
                canPlay = connected && (status == PlaybackStatus.Ready || status == PlaybackStatus.Paused ||
                    (status == PlaybackStatus.Stopped && lastPausePositionMs == null)),
                canPause = connected && status == PlaybackStatus.Playing,
                canStop = controller?.availableSessionCommands?.contains(StopAndResetCommand) == true &&
                    (status == PlaybackStatus.Playing || status == PlaybackStatus.Paused),
                connectionFailed = connectionFailed,
            )
        }

    fun play() {
        val player = controller ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun stop() {
        controller?.sendCustomCommand(StopAndResetCommand, Bundle.EMPTY)
    }

    /**
     * Called from MainActivity.onStart() to connect the screen to the media session in
     * [PlaybackService]. Once the controller is ready, the screen can send playback commands.
     */
    fun connect() {
        if (controllerFuture != null) return
        connectionFailed = false
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MainViewModel.controller === controller) {
                        this@MainViewModel.controller = null
                        connectionFailed = true
                    }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            // The connection is asynchronous. Ignore its result if the screen stopped or reconnected.
            if (controllerFuture === future) {
                try {
                    controller = future.get()
                } catch (error: Exception) {
                    Log.e("PodcastDemo", "Controller connection failed", error)
                    connectionFailed = true
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * Releases the screen's controller when the Activity stops or this ViewModel is cleared.
     * [PlaybackService] owns the player, so playback can continue in the background.
     */
    fun disconnect() {
        val future = controllerFuture
        // Clear the active request first so a late callback cannot reattach this controller.
        controllerFuture = null
        controller = null
        future?.let { MediaController.releaseFuture(it) }
    }

    override fun onCleared() {
        disconnect()
    }
}
