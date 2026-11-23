# Podcast StateStore demo

A single-screen Kotlin / Jetpack Compose app in `com.stevdza.podcastdemo`. Play a bundled 60-second audio fixture, pause from the app or Android's media controls, then resume from the checkpoint after a force stop. The screen displays the last saved checkpoint, not a continuously updating playback position.

## Source walkthrough

Read the main files in `app/src/main/java/com/stevdza/podcastdemo/` in this order:

1. **PodcastState.kt** — playback status, a persisted resume checkpoint, and a temporary last-pause key. The service and UI share one `PodcastState`. Its session store holds playback status and the temporary pause key. A dedicated checkpoint store keeps the set of keys observed by `syncToDataStore` stable across Pause and Stop, so auto-clearing a session key cannot restart checkpoint restoration. Only the resume key implements `PersistToDataStore<Long?>`. The temporary key uses `autoClearKey` and `shouldClearState` to remove itself when playback becomes `Stopped`.
2. **PodcastApplication.kt** — starts Koin and registers process-wide singletons for `StateStore`, `PodcastState`, and checkpoint persistence. It starts the persistence connection once.
3. **CheckpointPersistence.kt** — owns `files/podcast-resume.json`, runs `syncToDataStore`, and waits for a saved checkpoint to restore before playback preparation.
4. **PlaybackService.kt** — injects shared state and checkpoint persistence, and owns ExoPlayer and the MediaSession. Both pause controls reach `savePausePosition()`. The app and notification share the `STOP_AND_RESET` command, which publishes `Stopped`, pauses, and seeks to zero. Natural completion also saves `0L`.
5. **MainViewModel.kt** — reads the shared podcast state and checkpoint metadata to build the screen state. It owns the MediaController and sends Play/Pause/Stop commands.
6. **MainActivity.kt** — gets its ViewModel from Koin, connects it on start, disconnects it on stop, and renders the state with `PodcastScreen`.

`null` means no checkpoint has been saved. On a fresh install there is no checkpoint file. This demo owns one file containing one persisted value, so file existence is its restore guard. An existing file that does not restore a non-null value within 15 seconds causes an error; the player does not silently start from zero. Playback and persistence failures are logged under `PodcastDemo`.

## Dependencies and build

- AppState StateStore and StateStore DataStore: **1.0.0-alpha03**
- Media3 ExoPlayer and Session: **1.11.1**
- Kotlin Serialization core: **1.7.3** (built-in nullable Long serializer; no serialization compiler plugin)
- Koin Android: **4.2.2** for process-wide dependencies and Android entry-point injection
- Existing AGP **9.5.2**, Gradle **9.6.0**, Compose BOM **2026.02.01**, Activity Compose **1.13.0**
- Compose compiler plugin updated to **2.4.21** for the current Kotlin toolchain
- Existing compile/target SDK **37**, min SDK **26** retained
- Existing Gradle daemon toolchain file requests **JDK 25**

Official references: [StateStore release/API changes](https://developer.android.com/jetpack/androidx/releases/appstate-statestore), [StateStore guide](https://developer.android.com/topic/libraries/architecture/appstate/statestore), [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [Koin Android setup](https://insert-koin.io/docs/setup/).

Open this directory in Android Studio, sync, and run the `app` configuration. From a terminal:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.stevdza.podcastdemo/.MainActivity
```

The fixture is original synthesized demo audio: a changing sequence of gentle tones, mono 16-bit PCM at 22,050 Hz. It is already bundled at `app/src/main/res/raw/demo_episode.wav`, so playback needs no network connection. Regenerate it with `python3 tools/generate_demo_audio.py`. Local speech synthesis was unavailable in the implementation environment.

## Short recording sequence

1. Launch, tap **Play**, and let the clip play for roughly 10–15 seconds.
2. Open the notification shade / media controls and tap **Pause**. Return to the app: expect **Paused**, **Saved position 00:xx**, and **Resume**.
3. Confirm that the checkpoint has reached disk before stopping the process:

   ```bash
   adb shell run-as com.stevdza.podcastdemo cat files/podcast-resume.json
   ```

   Expect a nonzero value such as `{"resume_position_ms":"12500"}` matching the screen to the nearest second. The UI updates immediately; the DataStore write is asynchronous. Wait and check again if the file is missing or still contains an older value.

4. Force-stop, then open the app from its launcher icon (or use the second command):

   ```bash
   adb shell am force-stop com.stevdza.podcastdemo
   adb shell am start -n com.stevdza.podcastdemo/.MainActivity
   ```

5. Expect **Restoring…** briefly, then **Ready**, the saved timestamp, and **Resume**. Tap **Resume**. Check the service's actual playback position:

   ```bash
   adb logcat -d -s PodcastDemo:I
   ```

   Compare `Pause checkpoint: 12500ms`, `Preparing episode at 12500ms`, and `Playback started at ...ms` after the relaunch. Playback should start near the saved offset. A restored label with audio restarting at zero fails the check.

Also rehearse app-button pause, rotation during playback, leaving and returning to the Activity, and reaching the end: the checkpoint becomes `00:00`, and **Play** replays from the beginning. A fresh install should show **Ready**, **Play**, disabled **Pause** and **Stop**, and **Last pause this session · None**, with no saved-position label. Media3 provides the media-session notification; this demo does not request notification permission.

## Stop and automatic clearing

1. Play, then pause from the notification. **Last pause this session** and **Saved position** should show the same positive timestamp. Resume: the last-pause label keeps that value.
2. Tap **Stop** in the notification while playing or paused, then return to the app. The status becomes **Stopped**, the temporary label becomes **None**, and the checkpoint becomes **00:00**. **Play** waits for the temporary entry to disappear, then starts the loaded episode from zero. Repeat using the app's **Stop** button to demonstrate the same reset path.
3. Repeat Play → Pause → Stop. Each pause after a removal creates the entry and installs its auto-clear listener again.
4. Pause at a positive position, verify the JSON file with the command above, then force-stop/reopen: **Resume** should continue there, while **Last pause this session** is **None** in the new process.
5. Pause again, tap **Stop**, and verify `{"resume_position_ms":"0"}` on disk. Force-stop/reopen: **Play** should start at zero. Compare the existing preparation/playback logs to verify the actual player offset.

For the recording, show `autoClearKey = PlaybackStatusKey` and the `Stopped` predicate in `PodcastState.kt`. The UI reads `lastPausePositionMs` directly during composition: checking `store.keys` avoids recreating a removed entry, and avoids retaining an old `State` reference. In alpha03, the first `setState` must create the temporary entry to install automatic clearing.

Automatic removal applies only to the temporary in-memory key. `stopAndReset()` separately saves `0L` through the existing resume key because removing an entry does not erase its old DataStore value. The service sets `Stopped` before pausing so its internal pause cannot overwrite zero with a new pause checkpoint.

Notification Stop is a Media3 `CommandButton` bound to the same `STOP_AND_RESET` command. Its media button preference requests the forward slot beside the central Play/Pause control. The app and Media3's notification controller are allowed to run it; Android controls the final notification layout. Stop resets playback and leaves the episode loaded for replay.

## Verification status

The debug APK and instrumentation APK built successfully. Both `PodcastPersistenceTest` tests passed on the Android emulator.

The app-button check saved a nonzero pause position, restored it after a force-stop, and resumed playback there. Tapping Stop then showed **Stopped**, **Saved position 00:00**, **Last pause this session · None**, and **Play**. The checkpoint file contained zero, and the service prepared at 0 ms after another force-stop and relaunch.

`PodcastPersistenceTest` checks repeated pause/stop cycles and actual key removal. Its DataStore test uses three independently created stores: save a nonzero offset, restore it without restoring playback status or the temporary pause, record another pause, Stop, and restore zero. It uses an isolated cache file, never the demo checkpoint. Run it on a connected device with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The persistence test does not replace the notification / force-stop / actual-player-position rehearsal above.
