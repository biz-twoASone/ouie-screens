// android-tv/app/src/main/java/app/ouie/screens/playback/PlaybackScreen.kt
package app.ouie.screens.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ouie.screens.R
import app.ouie.screens.running.InitialSyncOverlay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PlaybackScreen(
    state: StateFlow<PlaybackState>,
    onAdvanceItem: () -> Unit,
) {
    val s by state.collectAsState()
    when (val cur = s) {
        PlaybackState.Syncing -> InitialSyncOverlay(message = "Syncing menu…")
        PlaybackState.NoPlaylist -> InitialSyncOverlay(message = "No playlist assigned", showSpinner = false)
        PlaybackState.EmptyPlaylist -> InitialSyncOverlay(message = "Playlist is empty", showSpinner = false)
        PlaybackState.Preparing -> PreparingScreen()
        is PlaybackState.Playing -> {
            when (cur.item.kind) {
                PlaybackItem.Kind.Video -> VideoPlayerHost(
                    file = cur.item.localFile,
                    generation = cur.generation,
                    onEnded = onAdvanceItem,
                )
                PlaybackItem.Kind.Image -> ImageSlideHost(
                    file = cur.item.localFile,
                    generation = cur.generation,
                    durationSeconds = cur.item.durationSeconds,
                    onTimeout = onAdvanceItem,
                )
            }
        }
    }
}

@Composable
private fun PreparingScreen() {
    Box(
        Modifier.fillMaxSize().background(colorResource(id = R.color.brand_paper)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Preparing content…", color = colorResource(id = R.color.brand_copper), fontSize = 20.sp)
    }
}
