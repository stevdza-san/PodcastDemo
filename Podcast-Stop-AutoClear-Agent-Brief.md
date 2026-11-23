# Add Stop and show StateStore clearing a playback position

Apply this to the existing single-episode podcast app. Use the existing `StateStore`, `PodcastState`, Media3 service, and player screen. Add one **Stop** button beside the current controls and one **Last pause this session** label. The notification’s Pause button continues to use the existing playback flow.

| Action | In-memory pause position | Position restored on the next launch |
| --- | --- | --- |
| Pause from the app or media notification at 00:12 | Shows 00:12 | Restores 00:12 after the write completes |
| Play again | Keeps the last pause position | Keeps the last saved position |
| Tap **Stop** in the app | StateStore removes the entry | Starts at 00:00 after the reset is saved |

Keep the single episode and current dependencies. Skip transcript UI and catalog changes for this demo.

## 1. Add a conditional key in PodcastState.kt

Add `Stopped` to the existing `PlaybackStatus` enum. Add this key next to the existing keys.

~~~kotlin
private object LastPausePositionKey : StateStoreKey<Long?>(
    defaultValue = null,
    autoClearKey = PlaybackStatusKey,
    shouldClearState = { store ->
        store.getState(PlaybackStatusKey).value == PlaybackStatus.Stopped
    },
)
~~~

`autoClearKey` names the key to watch. `shouldClearState` checks its new value. StateStore removes the `LastPausePositionKey` entry when the condition passes.

Add these members to `PodcastState`. Keep the existing `resumePositionMs` and `saveResumePosition()` for the DataStore-backed checkpoint.

~~~kotlin
val lastPausePositionMs: Long?
    get() = if (LastPausePositionKey in store.keys) {
        store.getState(LastPausePositionKey).value
    } else {
        null
    }

fun recordPause(positionMs: Long) {
    val position = positionMs.coerceAtLeast(0L)
    setPlaybackStatus(PlaybackStatus.Paused)
    store.setState(LastPausePositionKey, position)
    saveResumePosition(position)
}

fun stopAndReset() {
    setPlaybackStatus(PlaybackStatus.Stopped)
    saveResumePosition(0L)
}
~~~

In AppState `1.0.0-alpha03`, let the first `setState()` create the temporary entry so it installs the auto-clear listener. The getter checks `store.keys` before looking up the value because `getState()` creates a missing entry. Read the getter directly during composition. A cached `State<Long?>` reference can still hold the previous value after removal. [StateStore API reference](https://developer.android.com/reference/kotlin/androidx/appstate/statestore/StateStore) · [StateStoreKey API reference](https://developer.android.com/reference/kotlin/androidx/appstate/statestore/StateStoreKey)

The temporary key is only used to show automatic removal in memory. The persisted key stays registered, and `saveResumePosition(0L)` writes the new checkpoint through the existing sync. In alpha03, the integration saves non-null changes and leaves the file’s old value in place when an in-memory entry disappears. [Published alpha03 DataStore integration source](https://dl.google.com/dl/android/maven2/androidx/appstate/statestore/statestore-datastore-android/1.0.0-alpha03/statestore-datastore-android-1.0.0-alpha03-sources.jar)

## 2. Handle Stop inside the existing media service

Route the app button through the existing `MediaController` and `MediaSession`. Register an app-only command called `STOP_AND_RESET`. It pauses the player and seeks to zero, leaving the loaded episode ready for the next Play.

In `PlaybackService.kt`, add the command at file scope so the Activity can use it.

~~~kotlin
internal val StopAndResetCommand = SessionCommand(
    "com.example.podcaststatestore.STOP_AND_RESET",
    Bundle.EMPTY,
)
~~~

Use the project’s own package prefix. Add the following overrides to the service’s existing `MediaSession.Callback`. If the service uses the default callback, pass an object implementing `MediaSession.Callback` to `MediaSession.Builder.setCallback()`. The example uses Media3 `1.11.1` and expects `podcast` to be the existing state holder.

~~~kotlin
override fun onConnectAsync(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
): ListenableFuture<MediaSession.ConnectionResult> {
    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
    if (controller.packageName == packageName) {
        commands.add(StopAndResetCommand)
    }
    return Futures.immediateFuture(
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands.build())
            .build()
    )
}

override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
): ListenableFuture<SessionResult> {
    if (controller.packageName != packageName || customCommand != StopAndResetCommand) {
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    podcast.stopAndReset()
    session.player.pause()
    session.player.seekTo(0L)
    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
}
~~~

If you already configure session commands, add the new command to the existing list and keep the current connection rules. Import `android.os.Bundle`, Media3’s `SessionCommand` and `SessionResult`, and Guava’s `Futures` and `ListenableFuture`. Keep the default media notification controls. [Media3 custom commands](https://developer.android.com/media/media3/session/control-playback)

In the current pause listener, keep the explicit user-pause checks and replace the existing status-and-position writes with `podcast.recordPause(player.currentPosition)`. Guard that call with `podcast.playbackStatus.value != PlaybackStatus.Stopped`. The Stop handler publishes `Stopped` before it calls `pause()`, so that internal pause must skip the checkpoint save. Keep `Stopped` through the pause and seek callbacks. Publish `Playing` when playback actually starts again. Update any exhaustive status branches to include `Stopped`.

Trigger the reset from the explicit Stop command. Media3’s `STATE_IDLE` also occurs during startup and after playback errors. An Android force stop is used to test what was already saved to disk. [Media3 player events](https://developer.android.com/media/media3/exoplayer/listening-to-player-events)

## 3. Add the two UI elements

Add an outlined **Stop** button next to Play and Pause. Enable it while the controller is connected, has the custom command in `availableSessionCommands`, and playback is **Playing** or **Paused**. The click sends `controller.sendCustomCommand(StopAndResetCommand, Bundle.EMPTY)`.

Read `podcast.lastPausePositionMs` once in the composable and show **Last pause this session · 00:12** or **Last pause this session · None**. Use the existing timestamp formatter. Drive the label from that getter so viewers see StateStore actually remove the entry. While the status is `Stopped` and the temporary value still exists, keep Play disabled until the removal arrives. When ready, the primary action reads **Play** because the saved position is zero.

## Verify the demo

1. Play for a few seconds and pause from the Android notification. Check that the in-memory label and saved checkpoint have the same positive position. Resume and check that the label still shows the last pause.
2. Tap **Stop**. Check that playback stops, the label changes to **None**, and **Play** starts at zero. Repeat Pause → Stop to check that a new entry installs the auto-clear rule again.
3. Pause at a positive position and confirm the checkpoint reached disk. Force-stop and relaunch. **Resume** should play from that position.
4. Pause at a positive position again, tap **Stop**, and wait for the zero checkpoint to reach disk. Force-stop and relaunch. **Play** should start from the beginning. For debug verification, use the existing DataStore inspection command from the podcast brief.
5. Run the debug build and report which checks you performed on a device or emulator.

For the recording, show `autoClearKey = PlaybackStatusKey` and the `Stopped` check while the label changes to **None**. Then show the single zero write that makes the reset survive a restart.
