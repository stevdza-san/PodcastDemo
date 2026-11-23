package com.stevdza.podcastdemo.di

import androidx.appstate.statestore.StateStore
import com.stevdza.podcastdemo.CheckpointPersistence
import com.stevdza.podcastdemo.PodcastState
import org.koin.dsl.module

val podcastModule = module {
    single { StateStore() }
    single { PodcastState(get()) }
    single { CheckpointPersistence(get(), get(), get()) }
}