package app.ouie.screens.error

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ouie.screens.BuildConfig
import app.ouie.screens.R
import app.ouie.screens.state.AppState
import kotlinx.coroutines.delay

/**
 * Terminal-ish failure screen with an auto-retry countdown.
 *
 * The headline stays deliberately calm and non-technical — these TVs hang in a
 * café dining room and guests read them. The technical truth goes in the small
 * dim footer ([detail] + build id), which is legible in a phone photo taken up
 * close but is visual noise from across the room. That footer is the entire
 * point: before it existed, every failure rendered as "Can't reach our server"
 * and the 2026-06/07 ESSEL Bogor outage could not be diagnosed remotely.
 */
@Composable
fun ErrorScreen(kind: AppState.ErrorKind, detail: String? = null, onRetry: () -> Unit) {
    val (title, hint, autoRetrySec) = when (kind) {
        AppState.ErrorKind.NetworkUnavailable ->
            Triple("No network", "Retrying automatically when the TV reconnects.", 10)
        AppState.ErrorKind.ServerUnavailable ->
            Triple("Can't reach our server", "Will retry shortly.", 10)
        AppState.ErrorKind.SecureConnectionFailed ->
            Triple(
                "Can't connect securely",
                "Usually the TV's date & time is wrong. Check Settings › Date & time.",
                10,
            )
        AppState.ErrorKind.TokensInvalid ->
            Triple("Device needs re-pairing", "Starting pairing again…", 3)
        AppState.ErrorKind.Unknown ->
            Triple("Something went wrong", "Retrying shortly.", 10)
    }

    var remaining by remember(kind, detail) { mutableIntStateOf(autoRetrySec) }
    LaunchedEffect(kind, detail) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
        onRetry()
    }

    val paper = colorResource(id = R.color.brand_paper)
    val copper = colorResource(id = R.color.brand_copper)
    val copperDeep = colorResource(id = R.color.brand_copper_deep)

    Box(Modifier.fillMaxSize().background(paper).padding(48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = copperDeep, fontSize = 28.sp, textAlign = TextAlign.Center)
            Text(hint, color = copper, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text("Retrying in $remaining s", color = copper, fontSize = 14.sp)
        }

        // Diagnostic footer. Deliberately low-contrast and small: readable in a
        // close-up photo, ignorable from a dining table.
        Text(
            text = listOfNotNull(detail, "build ${BuildConfig.VERSION_NAME}").joinToString("  ·  "),
            color = copper.copy(alpha = 0.55f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
