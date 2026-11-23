package com.stevdza.podcastdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stevdza.podcastdemo.screen.PodcastScreen
import com.stevdza.podcastdemo.ui.theme.PodcastDemoTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PodcastDemoTheme {
                PodcastScreen(
                    state = viewModel.uiState,
                    onPlay = viewModel::play,
                    onPause = viewModel::pause,
                    onStop = viewModel::stop,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.connect()
    }

    override fun onStop() {
        viewModel.disconnect()
        super.onStop()
    }
}
