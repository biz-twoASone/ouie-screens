// android-tv/app/src/main/java/app/ouie/screens/playback/PlaybackScreen.kt
package app.ouie.screens.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        is PlaybackState.Preparing -> PreparingScreen(cur)
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
private fun PreparingScreen(state: PlaybackState.Preparing) {
    val copper = colorResource(id = R.color.brand_copper)
    val copperDeep = colorResource(id = R.color.brand_copper_deep)
    val errorColor = Color(0xFFC62828)

    val allFailed = state.totalItems > 0 && state.failedItems >= state.totalItems

    Box(
        Modifier.fillMaxSize().background(colorResource(id = R.color.brand_paper)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                text = if (allFailed) "Unable to download content" else "Preparing content…",
                color = if (allFailed) errorColor else copper,
                fontSize = 20.sp,
            )

            if (state.totalItems > 0) {
                Spacer(modifier = Modifier.height(16.dp))

                // Progress bar
                val fraction = if (state.totalItems > 0) {
                    (state.cachedItems.toFloat() / state.totalItems).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(copperDeep.copy(alpha = 0.2f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(copper),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress text
                if (!allFailed) {
                    val downloaded = state.cachedItems - state.failedItems
                    Text(
                        text = "Downloading ${downloaded + 1} of ${state.totalItems}…",
                        color = copperDeep,
                        fontSize = 14.sp,
                    )
                }
            }

            // Error display
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (allFailed) {
                        state.lastError
                    } else {
                        "${state.failedItems} failed — ${state.lastError}"
                    },
                    color = errorColor.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
