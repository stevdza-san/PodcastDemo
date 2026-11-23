package com.stevdza.podcastdemo

import androidx.appstate.datastore.syncToDataStore
import androidx.appstate.statestore.StateStore
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastPersistenceTest {
    @Test
    fun stopRemovesTemporaryEntryAndEachPauseReinstallsAutoClear() = runBlocking(Dispatchers.Main) {
        val store = StateStore()
        val podcast = PodcastState(store)
        assertNull(podcast.lastPausePositionMs)
        assertEquals(2, store.keys.size) // Reading an absent pause must not register its key.

        repeat(2) { cycle ->
            val position = (cycle + 1) * 12_500L
            podcast.savePausePosition(position)
            assertEquals(3, store.keys.size)
            podcast.setPlaybackStatus(PlaybackStatus.Playing)
            Snapshot.sendApplyNotifications()
            assertEquals(position, podcast.lastPausePositionMs)

            podcast.stopAndReset()
            awaitCondition { podcast.lastPausePositionMs == null }
            assertEquals("The temporary entry was removed", 2, store.keys.size)
            assertEquals(PlaybackStatus.Stopped, podcast.playbackStatus.value)
            assertEquals(0L, podcast.resumePositionMs.value)
        }
    }

    @Test
    fun checkpointRestoresInANewStoreAndZeroReplacesAnOldCheckpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Never touch the demo's real checkpoint or run two DataStores against the same file.
        val directory = File(context.cacheDir, "checkpoint-test-${UUID.randomUUID()}")
        directory.mkdirs()
        val file = File(directory, "resume.json")
        try {
            withSyncedStore(file) { podcast ->
                assertNull(podcast.resumePositionMs.value)
                podcast.setPlaybackStatus(PlaybackStatus.Paused)
                podcast.saveResumePosition(12_500L)
                awaitDiskPosition(file, 12_500L)
                val json = withContext(Dispatchers.IO) { JSONObject(file.readText()) }
                assertEquals("Only the checkpoint is persisted", 1, json.length())
            }

            withSyncedStore(file) { podcast ->
                awaitCondition { podcast.resumePositionMs.value == 12_500L }
                assertEquals(PlaybackStatus.Preparing, podcast.playbackStatus.value)
                assertNull(podcast.lastPausePositionMs)
                podcast.savePausePosition(20_000L)
                awaitDiskPosition(file, 20_000L)
                podcast.stopAndReset()
                awaitCondition { podcast.lastPausePositionMs == null }
                awaitDiskPosition(file, 0L)
            }

            withSyncedStore(file) { podcast ->
                awaitCondition { podcast.resumePositionMs.value == 0L }
                assertEquals(PlaybackStatus.Preparing, podcast.playbackStatus.value)
                assertNull(podcast.lastPausePositionMs)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun withSyncedStore(file: File, block: suspend (PodcastState) -> Unit) {
        withContext(Dispatchers.Main) {
            coroutineScope {
                val store = StateStore()
                val podcast = PodcastState(store)
                val sync = launch { store.syncToDataStore(file.absolutePath, this) }
                try {
                    block(podcast)
                } finally {
                    sync.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun awaitDiskPosition(file: File, positionMs: Long) {
        awaitCondition {
            withContext(Dispatchers.IO) {
                file.exists() && JSONObject(file.readText())
                    .optString("resume_position_ms") == positionMs.toString()
            }
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) {
                // This test has no Compose window to advance global snapshot notifications.
                Snapshot.sendApplyNotifications()
                delay(20)
            }
        }
    }
}
