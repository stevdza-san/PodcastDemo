package com.stevdza.podcastdemo

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal val StopAndResetCommand = SessionCommand(
    "com.stevdza.podcastdemo.STOP_AND_RESET",
    Bundle.EMPTY,
)

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PodcastApplication
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        app.podcast.setPlaybackStatus(PlaybackStatus.Preparing)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(playbackListener)
        }
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        scope.launch {
            try {
                // This demo owns one file with one nullable persisted key. Never prepare at
                // zero while a checkpoint from an earlier process is still being restored.
                val startPositionMs = if (app.hasCheckpointFileAtLaunch) {
                    checkNotNull(withTimeoutOrNull(15_000.milliseconds) {
                        snapshotFlow { app.podcast.resumePositionMs.value }
                            .filterNotNull().first()
                    }) { "Checkpoint was not restored within 15 seconds" }
                } else {
                    app.podcast.resumePositionMs.value ?: 0L
                }
                val episode = MediaItem.Builder()
                    .setMediaId("demo-episode")
                    .setUri("android.resource://$packageName/${R.raw.demo_episode}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.episode_title))
                            .setArtist(getString(R.string.app_name))
                            .build()
                    )
                    .build()
                Log.i("PodcastDemo", "Preparing episode at ${startPositionMs}ms")
                player.setMediaItem(episode, startPositionMs)
                player.prepare()
                // The listener publishes Ready once the player has actually prepared.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("PodcastDemo", "Episode preparation / checkpoint restore failed", error)
                app.podcast.setPlaybackStatus(PlaybackStatus.Error)
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val isNotification = session.isMediaNotificationController(controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (controller.packageName == packageName || isNotification) {
                commands.add(StopAndResetCommand)
            }
            val connection = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
            if (isNotification) {
                connection.setMediaButtonPreferences(listOf(
                    CommandButton.Builder(CommandButton.ICON_STOP)
                        .setDisplayName(getString(R.string.stop))
                        .setSessionCommand(StopAndResetCommand)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build()
                ))
            }
            return Futures.immediateFuture(connection.build())
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val canReset = controller.packageName == packageName ||
                session.isMediaNotificationController(controller)
            if (!canReset || customCommand != StopAndResetCommand) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            // Publish Stopped first so the internal pause cannot save the old position.
            app.podcast.stopAndReset()
            session.player.pause()
            session.player.seekTo(0L)
            Log.i("PodcastDemo", "Stop: checkpoint reset and player seek to 0ms")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && player.currentMediaItem != null &&
                player.playbackState != Player.STATE_ENDED && player.playerError == null &&
                app.podcast.playbackStatus.value != PlaybackStatus.Stopped
            ) {
                // Both the app button and Android's notification reach this callback.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    val positionMs = player.currentPosition
                    app.podcast.savePausePosition(positionMs)
                    Log.i("PodcastDemo", "Pause checkpoint: ${positionMs}ms")
                } else {
                    app.podcast.setPlaybackStatus(PlaybackStatus.Paused)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                app.podcast.setPlaybackStatus(PlaybackStatus.Playing)
                Log.i("PodcastDemo", "Playback started at ${player.currentPosition}ms")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (app.podcast.playbackStatus.value == PlaybackStatus.Preparing) {
                        app.podcast.setPlaybackStatus(PlaybackStatus.Ready)
                    }
                }
                Player.STATE_ENDED -> {
                    if (app.podcast.playbackStatus.value != PlaybackStatus.Stopped) {
                        app.podcast.saveResumePosition(0L)
                        app.podcast.setPlaybackStatus(PlaybackStatus.Ready)
                        Log.i("PodcastDemo", "Episode ended; checkpoint reset to 0ms")
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PodcastDemo", "Playback failed", error)
            app.podcast.setPlaybackStatus(PlaybackStatus.Error)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        player.removeListener(playbackListener)
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
