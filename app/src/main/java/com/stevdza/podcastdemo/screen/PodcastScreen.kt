package com.stevdza.podcastdemo.screen

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stevdza.podcastdemo.PlaybackStatus
import com.stevdza.podcastdemo.PodcastUiState
import com.stevdza.podcastdemo.R
import com.stevdza.podcastdemo.ui.theme.PodcastDemoTheme
import java.util.Locale

@Composable
internal fun PodcastScreen(
    state: PodcastUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val statusText = stringResource(when (state.status) {
        PlaybackStatus.Preparing -> if (state.restoring) R.string.restoring else R.string.preparing
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
            if (state.savedPositionMs != null) {
                Text(stringResource(R.string.saved_position, formatPosition(state.savedPositionMs)))
            }
            Text(stringResource(
                R.string.last_pause_this_session,
                state.lastPausePositionMs?.let(::formatPosition) ?: stringResource(R.string.none),
            ))
            if (state.connectionFailed) {
                Text(stringResource(R.string.connection_error), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    enabled = state.canPlay,
                ) {
                    Text(stringResource(if ((state.savedPositionMs ?: 0L) > 0L) R.string.resume else R.string.play))
                }
                OutlinedButton(onClick = onPause, enabled = state.canPause) {
                    Text(stringResource(R.string.pause))
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.canStop,
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
            state = PodcastUiState(
                status = PlaybackStatus.Paused,
                savedPositionMs = 12_000L,
                lastPausePositionMs = 12_000L,
                restoring = false,
                canPlay = true,
                canPause = false,
                canStop = true,
                connectionFailed = false,
            ),
            onPlay = {},
            onPause = {},
            onStop = {},
        )
    }
}
