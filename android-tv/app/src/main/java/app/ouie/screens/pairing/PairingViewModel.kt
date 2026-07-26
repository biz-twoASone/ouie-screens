// android-tv/app/src/main/java/app/ouie/screens/pairing/PairingViewModel.kt
package app.ouie.screens.pairing

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ouie.screens.auth.TokenSource
import app.ouie.screens.net.RecoveryAdapter
import app.ouie.screens.state.AppState
import app.ouie.screens.state.AppStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Drives the Pairing screen:
 * 0. On init, if a long-lived identity token is present (set on a prior
 *    pair / re-pair), attempt /screens-recover BEFORE showing a pairing
 *    code. This is the cold-boot recovery path — the TV was running fine,
 *    a transient blip nuked the refresh token, app got power-cycled,
 *    [TokenStore.loadSync] returns null at boot, but the identity token
 *    survives [TokenStore.clear] and re-establishes the session without
 *    the operator touching anything.
 * 1. If recovery fails or no identity token exists, request a code.
 * 2. Start polling `/pairing-status` every 3s.
 * 3. On Paired: persist tokens, transition AppState to Running.
 * 4. On Expired / PickupConsumed: request a new code and restart polling.
 * 5. On Error: surface via AppState.Error; auto-retry from ErrorScreen.
 */
class PairingViewModel(
    private val repo: PairingRepository,
    private val tokenStore: TokenSource,
    private val recoveryAdapter: RecoveryAdapter,
    private val appState: AppStateHolder,
) : ViewModel() {

    data class UiState(
        val code: String? = null,
        val expiresAtIso: String? = null,
        val secondsUntilExpiry: Int = 0,
        val isRequesting: Boolean = true,
        val message: String? = null,
        val recovering: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        start()
    }

    private fun start() {
        viewModelScope.launch {
            if (tryIdentityRecovery()) return@launch
            loop()
        }
    }

    /**
     * Cold-boot identity recovery. Returns true when recovery succeeded
     * and the app has transitioned to Running — caller should NOT proceed
     * to the pairing-code loop. Returns false when there's no identity
     * token or recovery failed; caller falls through to normal pairing.
     */
    private suspend fun tryIdentityRecovery(): Boolean {
        val identityToken = tokenStore.loadIdentitySync() ?: return false
        _ui.value = UiState(isRequesting = true, recovering = true, message = "Reconnecting…")
        return try {
            val recovered = recoveryAdapter.recover(identityToken, screenId = null)
            tokenStore.save(recovered)
            appState.toRunning(recovered.screenId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "identity recovery failed, falling back to pairing code", t)
            // Server signalled "no recoverable identity" (deleted screen,
            // revoked, identity-token secret rotation). Wipe the dead
            // identity token so we don't loop on the next start().
            if (t is app.ouie.screens.net.RecoveryFailedException && t.httpCode == 401) {
                tokenStore.clearAll()
            }
            _ui.value = UiState(isRequesting = true, recovering = false, message = null)
            false
        }
    }

    private suspend fun loop() {
        while (true) {
            _ui.value = UiState(isRequesting = true, message = _ui.value.message)
            val code = try {
                repo.requestCode()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "requestCode failed", t)
                appState.toError(AppState.ErrorKind.ServerUnavailable)
                return
            }
            _ui.value = UiState(
                code = code.code,
                expiresAtIso = code.expiresAtIso,
                secondsUntilExpiry = secondsUntil(code.expiresAtIso),
                isRequesting = false,
                message = null,
            )

            when (val result = repo.observeClaim(code.code)) {
                is PairingRepository.ClaimResult.Paired -> {
                    tokenStore.save(result.pickup.tokens)
                    result.pickup.identityToken?.let { tokenStore.saveIdentity(it) }
                    appState.toRunning(result.pickup.tokens.screenId)
                    return
                }
                PairingRepository.ClaimResult.Expired,
                PairingRepository.ClaimResult.PickupConsumed -> {
                    _ui.value = _ui.value.copy(message = "Code expired — generating a new one…")
                    // loop — request a new code
                }
                is PairingRepository.ClaimResult.Error -> {
                    Log.w(TAG, "observeClaim failed", result.cause)
                    appState.toError(AppState.ErrorKind.NetworkUnavailable)
                    return
                }
                PairingRepository.ClaimResult.Pending -> {} // observeClaim never returns Pending
            }
        }
    }

    private fun secondsUntil(iso: String): Int =
        ((Instant.parse(iso).toEpochMilli() - System.currentTimeMillis()) / 1000).toInt()
            .coerceAtLeast(0)

    companion object {
        private const val TAG = "PairingViewModel"
    }
}
