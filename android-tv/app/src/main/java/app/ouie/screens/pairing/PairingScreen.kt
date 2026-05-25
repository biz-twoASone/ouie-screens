// android-tv/app/src/main/java/app/ouie/screens/pairing/PairingScreen.kt
package app.ouie.screens.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ouie.screens.R
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@Composable
fun PairingScreen(viewModel: PairingViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val paper = colorResource(id = R.color.brand_paper)
    val copper = colorResource(id = R.color.brand_copper)
    val copperDeep = colorResource(id = R.color.brand_copper_deep)

    Box(
        modifier = Modifier.fillMaxSize().background(paper).padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "Pair this TV",
                color = copperDeep,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
            )

            if (ui.isRequesting || ui.code == null) {
                CircularProgressIndicator(color = copper)
                Text("Requesting pairing code…", color = copper, fontSize = 16.sp)
            } else {
                Text(
                    text = "Enter this code in your dashboard:",
                    color = copper,
                    fontSize = 18.sp,
                )
                Text(
                    text = ui.code!!,
                    color = copperDeep,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                CountdownText(expiresAtIso = ui.expiresAtIso)
            }
            ui.message?.let { Text(it, color = Color(0xFFB8860B), fontSize = 16.sp) }
        }
    }
}

@Composable
private fun CountdownText(expiresAtIso: String?) {
    if (expiresAtIso == null) return
    var remaining by remember(expiresAtIso) {
        mutableIntStateOf(
            ((java.time.Instant.parse(expiresAtIso).toEpochMilli() - System.currentTimeMillis()) / 1000)
                .toInt().coerceAtLeast(0)
        )
    }
    LaunchedEffect(expiresAtIso) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    Text(
        text = if (remaining > 0) "Code expires in $remaining s" else "Refreshing…",
        color = Color(0xFF9A7B5C),
        fontSize = 14.sp,
    )
}
