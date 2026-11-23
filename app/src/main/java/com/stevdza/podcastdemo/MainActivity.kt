package com.stevdza.podcastdemo

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.stevdza.podcastdemo.ui.theme.PodcastDemoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var connectionError by mutableStateOf<String?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PodcastApplication
        setContent {
            val status by app.podcast.playbackStatus
            val savedPositionMs by app.podcast.resumePositionMs
            val lastPausePositionMs = app.podcast.lastPausePositionMs
            PodcastDemoTheme {
                PodcastScreen(
                    status = status,
                    savedPositionMs = savedPositionMs,
                    lastPausePositionMs = lastPausePositionMs,
                    restoring = app.hasCheckpointFileAtLaunch && savedPositionMs == null,
                    connected = controller != null,
                    stopAvailable = controller?.availableSessionCommands?.contains(StopAndResetCommand) == true,
                    connectionError = connectionError,
                    onPlay = {
                        controller?.let {
                            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0L)
                            it.play()
                        }
                    },
                    onPause = { controller?.pause() },
                    onStop = { controller?.sendCustomCommand(StopAndResetCommand, Bundle.EMPTY) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectionError = null
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MainActivity.controller === controller) {
                        this@MainActivity.controller = null
                        connectionError = getString(R.string.connection_error)
                    }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            // An old Activity connection may complete after onStop or a newer onStart.
            if (controllerFuture === future) {
                try {
                    controller = future.get()
                } catch (error: Exception) {
                    Log.e("PodcastDemo", "Controller connection failed", error)
                    connectionError = getString(R.string.connection_error)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        val future = controllerFuture
        controllerFuture = null
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        super.onStop()
    }
}

@Composable
private fun PodcastScreen(
    status: PlaybackStatus,
    savedPositionMs: Long?,
    lastPausePositionMs: Long?,
    restoring: Boolean,
    connected: Boolean,
    stopAvailable: Boolean,
    connectionError: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val statusText = stringResource(when (status) {
        PlaybackStatus.Preparing -> if (restoring) R.string.restoring else R.string.preparing
        PlaybackStatus.Ready -> R.string.ready
        PlaybackStatus.Playing -> R.string.playing
        PlaybackStatus.Paused -> R.string.paused
        PlaybackStatus.Stopped -> R.string.stopped
        PlaybackStatus.Error -> R.string.playback_error
    })
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.episode_title), style = MaterialTheme.typography.headlineMedium)
            Text(statusText, style = MaterialTheme.typography.titleLarge)
            if (savedPositionMs != null) {
                Text(stringResource(R.string.saved_position, formatPosition(savedPositionMs)))
            }
            Text(stringResource(
                R.string.last_pause_this_session,
                lastPausePositionMs?.let(::formatPosition) ?: stringResource(R.string.none),
            ))
            if (connectionError != null) {
                Text(connectionError, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    enabled = connected && (status == PlaybackStatus.Ready || status == PlaybackStatus.Paused ||
                        (status == PlaybackStatus.Stopped && lastPausePositionMs == null)),
                ) {
                    Text(stringResource(if ((savedPositionMs ?: 0L) > 0L) R.string.resume else R.string.play))
                }
                OutlinedButton(onClick = onPause, enabled = connected && status == PlaybackStatus.Playing) {
                    Text(stringResource(R.string.pause))
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = connected && stopAvailable &&
                        (status == PlaybackStatus.Playing || status == PlaybackStatus.Paused),
                ) {
                    Text(stringResource(R.string.stop))
                }
            }
        }
    }
}

private fun formatPosition(positionMs: Long): String {
    val seconds = positionMs / 1_000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Preview(showBackground = true)
@Composable
private fun PodcastPreview() {
    PodcastDemoTheme {
        PodcastScreen(
            status = PlaybackStatus.Paused,
            savedPositionMs = 12_000L,
            lastPausePositionMs = 12_000L,
            restoring = false,
            connected = true,
            stopAvailable = true,
            connectionError = null,
            onPlay = {},
            onPause = {},
            onStop = {},
        )
    }
}
