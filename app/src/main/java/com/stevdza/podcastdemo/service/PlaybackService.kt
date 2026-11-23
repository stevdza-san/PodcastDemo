package com.stevdza.podcastdemo.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
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
import com.stevdza.podcastdemo.CheckpointPersistence
import com.stevdza.podcastdemo.MainActivity
import com.stevdza.podcastdemo.PlaybackStatus
import com.stevdza.podcastdemo.PodcastState
import com.stevdza.podcastdemo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

internal val StopAndResetCommand = SessionCommand(
    "com.stevdza.podcastdemo.STOP_AND_RESET",
    Bundle.EMPTY,
)

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val podcast: PodcastState by inject()
    private val checkpoint: CheckpointPersistence by inject()
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        podcast.setPlaybackStatus(PlaybackStatus.Preparing)
        player = createPlayer()
        session = createSession(player)
        launchEpisodePreparation()
    }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(this).build().apply {
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

    private fun createSession(player: ExoPlayer): MediaSession = MediaSession.Builder(this, player)
        .setCallback(sessionCallback)
        .setSessionActivity(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun launchEpisodePreparation() {
        scope.launch {
            try {
                val startPositionMs = checkpoint.positionForPreparation()
                Log.i("PodcastDemo", "Preparing episode at ${startPositionMs}ms")
                player.setMediaItem(createEpisode(), startPositionMs)
                player.prepare()
                // The listener publishes Ready once the player has actually prepared.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("PodcastDemo", "Episode preparation / checkpoint restore failed", error)
                podcast.setPlaybackStatus(PlaybackStatus.Error)
            }
        }
    }

    private fun createEpisode(): MediaItem = MediaItem.Builder()
        .setMediaId("demo-episode")
        .setUri("android.resource://$packageName/${R.raw.demo_episode}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.episode_title))
                .setArtist(getString(R.string.app_name))
                .build()
        )
        .build()

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
            stopAndReset(session.player)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun stopAndReset(player: Player) {
        // Publish Stopped first so the internal pause cannot save the old position.
        podcast.stopAndReset()
        player.pause()
        player.seekTo(0L)
        Log.i("PodcastDemo", "Stop: checkpoint reset and player seek to 0ms")
    }

    private val playbackListener = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || newPosition.mediaItem == null) return

            val positionMs = newPosition.positionMs
            when (podcast.playbackStatus.value) {
                PlaybackStatus.Paused -> podcast.savePausePosition(positionMs)
                PlaybackStatus.Playing, PlaybackStatus.Ready -> podcast.saveResumePosition(positionMs)
                PlaybackStatus.Stopped -> {
                    // Stop seeks to zero internally. A later scrub away from zero selects a new pause point.
                    if (positionMs > 0L) podcast.savePausePosition(positionMs)
                }
                PlaybackStatus.Preparing, PlaybackStatus.Error -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && player.currentMediaItem != null &&
                player.playbackState != Player.STATE_ENDED && player.playerError == null &&
                podcast.playbackStatus.value != PlaybackStatus.Stopped
            ) {
                // Both the app button and Android's notification reach this callback.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    val positionMs = player.currentPosition
                    podcast.savePausePosition(positionMs)
                    Log.i("PodcastDemo", "Pause checkpoint: ${positionMs}ms")
                } else {
                    podcast.setPlaybackStatus(PlaybackStatus.Paused)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                podcast.setPlaybackStatus(PlaybackStatus.Playing)
                Log.i("PodcastDemo", "Playback started at ${player.currentPosition}ms")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (podcast.playbackStatus.value == PlaybackStatus.Preparing) {
                        podcast.setPlaybackStatus(PlaybackStatus.Ready)
                    }
                }
                Player.STATE_ENDED -> {
                    if (podcast.playbackStatus.value != PlaybackStatus.Stopped) {
                        podcast.saveResumePosition(0L)
                        podcast.setPlaybackStatus(PlaybackStatus.Ready)
                        Log.i("PodcastDemo", "Episode ended; checkpoint reset to 0ms")
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PodcastDemo", "Playback failed", error)
            podcast.setPlaybackStatus(PlaybackStatus.Error)
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
