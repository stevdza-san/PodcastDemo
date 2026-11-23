package com.stevdza.podcastdemo.di

import androidx.appstate.statestore.StateStore
import com.stevdza.podcastdemo.CheckpointPersistence
import com.stevdza.podcastdemo.MainViewModel
import com.stevdza.podcastdemo.PodcastState
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val podcastModule = module {
    single { StateStore() }
    single { PodcastState(get()) }
    single { CheckpointPersistence(get(), get(), get()) }
    viewModel { MainViewModel(androidContext(), get(), get()) }
}