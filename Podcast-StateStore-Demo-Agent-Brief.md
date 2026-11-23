# Build a minimal Jetpack StateStore podcast demo

Build one small Android app in Kotlin and Jetpack Compose. It plays one bundled audio episode. The user can pause from the app or from Android’s media notification. After the app is force-stopped and opened again, it shows the saved position and offers **Resume**. Tapping **Resume** starts audio at that position.

The audience is learning Compose. Keep the state code short enough to walk through on camera. The demo should make two visible behaviors easy to explain.

1. The playback service updates live state and the Activity sees the change.
2. StateStore’s DataStore integration restores the pause position into a new process.

## User experience

| Moment | What the screen shows |
| --- | --- |
| First launch | Episode title, **Ready**, **Play**, and **Pause**. Pause is disabled until playback starts. |
| Playing | **Playing**. The Android media notification offers Pause. |
| Pause from the media notification | **Paused** and a checkpoint such as **Saved position 00:12**. |
| Relaunch after a force stop | **Restoring…** while loading an existing checkpoint, then **Saved position 00:12** and **Resume**. |
| Tap Resume | Audio begins at the restored offset and the screen shows **Playing**. |

Use one episode and one screen. The display needs the episode title, playback status, last saved position when available, and two buttons. The operating system provides the media notification. Use Material 3 defaults and put the teaching effort into readable code and a repeatable demo.

## Project structure

Use the existing Compose app module if the workspace has one. Otherwise start with a single-module **Empty Activity** Android project. Use `com.example.podcaststatestore` for a new project's package and application ID. Keep the app and service in the same Android process.

| File | Responsibility |
| --- | --- |
| `PodcastState.kt` | Defines two typed keys, exposes read-only Compose state, and owns the two state mutations. |
| `PodcastApplication.kt` | Creates one StateStore and starts the DataStore integration once per process. |
| `PlaybackService.kt` | Owns the Media3 player and media session, restores the initial position, and records a checkpoint when the user pauses. |
| `MainActivity.kt` | Connects to the service and renders the Compose screen. |
| `AndroidManifest.xml` | Registers the Application, launcher Activity, and media playback service. |
| `res/raw/demo_episode.wav` | One bundled audio clip. Use the matching extension if the clip is MP3. |

Plain constructor injection and the Application are sufficient for this demo. Keep Media3 setup in the service and state management in `PodcastState`. The View code sends `play()` and `pause()` commands through the controller.

## Dependencies

Use the Compose template’s existing compiler setup, Activity Compose, and Material 3. Resolve AndroidX from `google()` and Kotlin libraries from `mavenCentral()`. Use the Android 17 SDK with `compileSdk = 37` for the pinned AppState artifacts. `minSdk = 24` is a suitable sample setting.

~~~kotlin
dependencies {
    implementation("androidx.appstate.statestore:statestore:1.0.0-alpha03")
    implementation("androidx.appstate.statestore:statestore-datastore:1.0.0-alpha03")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
~~~

The sample uses Kotlin Serialization’s built-in `Long` serializer, so the serialization runtime is sufficient for these keys. If building a fresh reference project, a suitable toolchain is AGP 9.5.0, Gradle 9.6.0, JDK 21, Kotlin Compose compiler plugin 2.4.21, Compose BOM 2026.09.00, and Activity Compose 1.13.0. Prefer the workspace’s existing compatible versions when adapting a project.

AppState is still in alpha. In `1.0.0-alpha03`, the persistence interface is `PersistToDataStore<T>` and the key supplies a stable storage name and a `KSerializer<T>`. Use that version’s API contract for the code below. [StateStore release notes](https://developer.android.com/jetpack/androidx/releases/appstate-statestore) · [PersistToDataStore API](https://developer.android.com/reference/kotlin/androidx/appstate/datastore/PersistToDataStore)

## 1. Store the playback status and pause position

| Value | Default | Lifetime |
| --- | --- | --- |
| `playbackStatus` | `Preparing` | In memory. The player service publishes the actual status for this process. |
| `resumePositionMs` | `null` | Restored from DataStore when a prior checkpoint exists. A non-null value is a saved position in milliseconds. |

Use one position value because the app plays a fixed episode. A typed key gives the store the type and default of its value. `getState` returns a Compose `State<T>`, which the screen can read using `by` or `.value`. The service calls the small mutation methods when the player emits events.

The following is the complete state holder for the demo. Replace the package line if needed.

~~~kotlin
package com.example.podcaststatestore

import androidx.appstate.datastore.PersistToDataStore
import androidx.appstate.statestore.StateStore
import androidx.appstate.statestore.StateStoreKey
import androidx.compose.runtime.State
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

enum class PlaybackStatus { Preparing, Ready, Playing, Paused }

private object PlaybackStatusKey : StateStoreKey<PlaybackStatus>(
    defaultValue = PlaybackStatus.Preparing,
)

private object ResumePositionKey :
    StateStoreKey<Long?>(defaultValue = null),
    PersistToDataStore<Long?> {
    override val name = "resume_position_ms"
    override val serializer: KSerializer<Long?> = Long.serializer().nullable
}

class PodcastState(private val store: StateStore) {
    val playbackStatus: State<PlaybackStatus> = store.getState(PlaybackStatusKey)
    val resumePositionMs: State<Long?> = store.getState(ResumePositionKey)

    fun setPlaybackStatus(status: PlaybackStatus) {
        store.setState(PlaybackStatusKey, status)
    }

    fun saveResumePosition(positionMs: Long) {
        store.setState(ResumePositionKey, positionMs.coerceAtLeast(0L))
    }
}
~~~

The important line for the tutorial is `PersistToDataStore<Long?>`. Both keys use the same in-memory store. The resume key opts into sharing its value with DataStore when the sync is running. Here `null` means a checkpoint has yet to be written. Use an actual number such as `0L` if the app needs to save a position at the beginning. [StateStore guide](https://developer.android.com/topic/libraries/architecture/appstate/statestore)

## 2. Connect the store to DataStore in the Application

Create `PodcastState` before starting sync. Its `getState` calls register both keys on the store. Give the app an application-owned coroutine scope and launch the suspend sync call once in `Application.onCreate()`. This single connection uses the dedicated app-private file `files/podcast-resume.json`.

~~~kotlin
package com.example.podcaststatestore

import android.app.Application
import androidx.appstate.datastore.syncToDataStore
import androidx.appstate.statestore.StateStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PodcastApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = StateStore()
    val podcast = PodcastState(store)
    var hasCheckpointFileAtLaunch = false
        private set

    override fun onCreate() {
        super.onCreate()
        val file = File(filesDir, "podcast-resume.json")
        hasCheckpointFileAtLaunch = file.exists()
        scope.launch {
            store.syncToDataStore(path = file.absolutePath, coroutineScope = scope)
        }
    }
}
~~~

On a fresh install, the service can initialize the player at zero. On a later launch, if this dedicated file already exists, wait for the non-null resume value to arrive through `PodcastState` before preparing the player. The service can use `snapshotFlow`, `filterNotNull()`, and `first()` in its own coroutine scope. This keeps the startup sequence tied to the restored state even if disk access is slow.

~~~kotlin
val startPositionMs = if (app.hasCheckpointFileAtLaunch) {
    snapshotFlow { app.podcast.resumePositionMs.value }.filterNotNull().first()
} else {
    app.podcast.resumePositionMs.value ?: 0L
}
~~~

Use `androidx.compose.runtime.snapshotFlow` and the corresponding `kotlinx.coroutines.flow` imports. Keep the Play button disabled during preparation. The file-existence check is a small guard for this particular demo, which owns one file containing one persisted value. StateStore performs the read, restore, and write. If storage or playback fails, make the failure visible in the UI or recording logs so it can be diagnosed. Treat a failed restore as a failure when verifying the resume path.

## 3. Connect Media3 to the store

Use a single `PlaybackService : MediaSessionService`. In `onCreate()`, publish `Preparing`, create an `ExoPlayer`, connect it to a `MediaSession`, and attach the player listener. Return the session from `onGetSession`. Let Media3 publish and update its default media notification. The notification’s Pause action goes to the same player that receives Pause from the Compose screen. [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)

Bundle one original 45–90 second spoken clip as a raw resource. An original recorded or locally synthesized voice clip is enough. If voice generation is unavailable, generate a small audible WAV fixture locally and label it as demo audio. Include the fixture in the project so the app can play it offline immediately after installation. Give the `MediaItem` a title using `MediaMetadata` so it is recognizable in the notification. Use the Android resource URI form `android.resource://<package>/<numeric-resource-id>`. [RawResourceDataSource reference](https://developer.android.com/reference/androidx/media3/datasource/RawResourceDataSource)

In the service’s startup coroutine, determine the initial offset using the restore logic above. Install the `MediaItem` with `player.setMediaItem(episode, startPositionMs)`, call `player.prepare()`, and publish `Ready` after setting up the episode. Playback begins when the user taps Play or Resume.

For the UI status, publish `Playing` when the player reports it is playing and `Paused` when the user pauses. Use Media3’s listener for these updates. In the explicit pause callback, capture `player.currentPosition` before releasing or resetting anything. Record the checkpoint here so the app button and notification action go through the same code.

~~~kotlin
override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady &&
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
        player.currentMediaItem != null &&
        player.playbackState != Player.STATE_ENDED
    ) {
        app.podcast.setPlaybackStatus(PlaybackStatus.Paused)
        app.podcast.saveResumePosition(player.currentPosition)
    }
}
~~~

In `onIsPlayingChanged`, publish `Playing` when `isPlaying` becomes true. On a natural end, publish `Ready` and save `0L` so the next Play starts at the beginning. Keep checkpoint writes tied to meaningful events. Pause is the one you will demonstrate. The playback position can advance inside Media3 without persisting each tick. [Media3 Player.Listener reference](https://developer.android.com/reference/androidx/media3/common/Player.Listener)

Use a service-owned coroutine scope for preparation, cancel it when the service is destroyed, and release the player and media session in `onDestroy()`. The checkpoint is written when the user pauses. The process can disappear after a force stop, so the demonstration depends on the already saved checkpoint.

### Manifest

Merge these entries into the template's manifest. Keep its launcher Activity. Use relative component names if the classes live in the app package.

~~~xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

<application android:name=".PodcastApplication">
    <service
        android:name=".PlaybackService"
        android:exported="true"
        android:foregroundServiceType="mediaPlayback">
        <intent-filter>
            <action android:name="androidx.media3.session.MediaSessionService" />
        </intent-filter>
    </service>
</application>
~~~

Use Android’s media-session notification path for the demo. These media notifications are exempt from the Android 13 notification permission behavior change. [Notification permission exemptions](https://developer.android.com/develop/ui/compose/notifications/notification-permission#exemptions)

## 4. Render the state and send controller commands

Connect `MainActivity` to `PlaybackService` through a `SessionToken` and `MediaController.Builder(...).buildAsync()`. Attach in `onStart()` and release the controller future in `onStop()` with `MediaController.releaseFuture(...)`. Keep the currently connected controller in observable state. Handle the future on the main executor and apply its result only if it still belongs to the Activity’s current connection attempt. Handle a failed connection with a short screen message. [Connect to a MediaSession](https://developer.android.com/media/media3/session/connect-to-media-app)

In the screen, read `podcast.playbackStatus` and `podcast.resumePositionMs` with `by`. Format the saved position for display as minutes and seconds. Show **Resume** for a saved position greater than zero and **Play** otherwise.

- **Play or Resume** calls `controller.play()` once the controller is connected and the episode is prepared. The initial offset was applied by the service. If the episode has ended, seek to the beginning before replaying.
- **Pause** calls `controller.pause()` while playing. The listener in the service takes care of saving.
- **Restoring…** or **Preparing…** is sufficient during startup. Use **Restoring…** when a launch is waiting for a previous checkpoint.

A live progress slider would need continuous player reads. The saved-position label is enough to demonstrate the API.

## 5. Verify the demonstration

Build the debug APK with `./gradlew :app:assembleDebug` for a standard `:app` module. If the workspace uses a different module name, use its assemble task. Use an emulator or Android device with media controls visible in the notification shade or Quick Settings.

| Check | Expected result |
| --- | --- |
| Fresh install, launch, tap Play | The local episode starts from zero and Android displays a media notification. |
| Let it play for roughly 10–15 seconds, then pause from the **notification** | Audio pauses and the screen shows **Paused** with the captured position. |
| Play again, then pause from the **app button** | The same service callback saves the later checkpoint. |
| Force-stop after the checkpoint is on disk and reopen from the launcher | The app rebuilds the player at the saved offset and shows **Resume**. |
| Tap Resume | Audio continues roughly where the user paused. The displayed timestamp can round to seconds. |
| Rotate during playback or reopen the Activity while the service is active | The Activity rejoins the active service. Playback continues without a second player being created by the screen. |

For rehearsal, confirm the checkpoint has reached disk before the force stop. The UI reads the in-memory checkpoint immediately, while DataStore writes asynchronously. With a debug build and the sample package, these commands let you inspect the demo file and stop the process without clearing its data.

~~~bash
adb shell run-as com.example.podcaststatestore cat files/podcast-resume.json
adb shell am force-stop com.example.podcaststatestore
~~~

After relaunch, verify the service actually applies the saved offset to the player. A restored number in the UI and a player resuming from zero would fail the test. During development, compare the timestamp logged at pause with the actual `player.currentPosition` when playback begins after Resume.

## Walkthrough for the recording

Show the notification Pause and the force-stop payoff before opening the editor. Then open `PodcastState.kt` first so viewers can see both keys together.

> The player is paused, and the app has saved this number. The number is just how far into the episode I was, in milliseconds. I’m going to stop the whole app and open it again. The player starts up fresh, but StateStore gets my saved position back through DataStore.
>
> These two values go into the same store. Playing or paused is live information from the player. The position is a checkpoint I want tomorrow. This interface tells the store to sync that one value, and this line in the Application connects the store to DataStore.
>
> Finally, here’s where the number comes from. I can pause from the screen or the notification. Both reach this listener in the service. The listener reads the player’s position and writes it to the store. When I launch a fresh process, this is the number the service uses to prepare the episode.

A simple singleton can share playback status across components in one process. In this demo, StateStore gives the values typed keys and lets the integration handle synchronizing the selected key with disk. Keep that distinction visible in the walkthrough.

## Handoff after implementation

Report the project entry point, the exact AppState and Media3 versions used, whether the debug build succeeded, and which device checks you actually ran. Provide the shortest sequence required to reproduce Pause from the notification → force-stop → reopen → Resume. Keep the source focused on the four Kotlin responsibilities above so the tutorial can follow it in that order.
