// android-tv/app/src/main/java/app/ouie/screens/MainActivity.kt
package app.ouie.screens

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.ouie.screens.auth.TokenSource
import app.ouie.screens.coordinator.RunningCoordinator
import app.ouie.screens.error.ErrorScreen
import app.ouie.screens.pairing.PairingScreen
import app.ouie.screens.running.RunningScreen
import app.ouie.screens.service.SignageService
import app.ouie.screens.state.AppState
import app.ouie.screens.state.AppStateHolder
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val appState: AppStateHolder by inject()
    private val tokenStore: TokenSource by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tokenStore.loadSync()?.let { appState.toRunning(it.deviceId) }
        setContent { SignageRoot(appState) }
    }
}

@Composable
private fun SignageRoot(appState: AppStateHolder) {
    val state by appState.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state) {
        when (state) {
            is AppState.Running -> ContextCompat.startForegroundService(
                context,
                Intent(context, SignageService::class.java),
            )
            else -> context.stopService(Intent(context, SignageService::class.java))
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            AppState.Pairing -> PairingScreen()
            is AppState.Running -> RunningScreen(deviceId = s.deviceId)
            is AppState.Error -> ErrorScreen(
                kind = s.kind,
                onRetry = { appState.recoverToPairing() },
            )
        }
    }
}
