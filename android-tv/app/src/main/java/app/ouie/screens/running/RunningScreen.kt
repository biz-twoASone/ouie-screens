// android-tv/app/src/main/java/app/ouie/screens/running/RunningScreen.kt
package app.ouie.screens.running

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.ouie.screens.coordinator.RunningCoordinator
import app.ouie.screens.playback.PlaybackScreen
import org.koin.compose.koinInject

@Composable
fun RunningScreen(screenId: String) {
    val coordinator: RunningCoordinator = koinInject()
    val director by coordinator.playbackDirector.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val d = director
        if (d != null) {
            PlaybackScreen(state = d.state, onAdvanceItem = { d.advanceItem() })
        }
        // While coordinator is still starting (a few hundred ms at most), keep
        // the screen black. No spinner — we never want to show loading chrome
        // to customers.
    }
}
