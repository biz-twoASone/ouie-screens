// android-tv/app/src/main/java/app/ouie/screens/error/FailureDiagnosis.kt
package app.ouie.screens.error

import app.ouie.screens.net.RecoveryFailedException
import app.ouie.screens.net.RefreshFailedException
import app.ouie.screens.pairing.PairingRepository
import app.ouie.screens.pairing.PairingStatusFailedException
import app.ouie.screens.pairing.UnknownPairingStatusException
import app.ouie.screens.state.AppState
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * An [AppState.ErrorKind] paired with the concrete cause that produced it.
 *
 * WHY THIS EXISTS: until 2026-07-26 every pairing-path failure was collapsed
 * into `ErrorKind.ServerUnavailable` → the TV rendered "Can't reach our
 * server" regardless of what actually happened. That single label made three
 * genuinely different faults indistinguishable from across the city:
 *   - DNS / no route (store network)
 *   - TLS handshake refusal (TV clock drift — the classic Android TV fault)
 *   - the server answering with an HTTP error (it WAS reachable)
 *
 * During the 2026-06-23 → 07-26 ESSEL Bogor Pajajaran outage all three TVs
 * showed that one screen, and the only way left to tell the cases apart was
 * a physical site visit with `adb logcat`. [detail] is rendered verbatim on
 * the error screen so the next diagnosis can be done from a phone photo.
 */
data class FailureDiagnosis(
    val kind: AppState.ErrorKind,
    /** Short, operator-readable cause — safe to render on a customer-facing TV. */
    val detail: String,
)

/**
 * Maps a thrown failure onto (kind, cause). Pure — no Android dependencies —
 * so it is unit-tested directly in `FailureClassifierTest`.
 *
 * DELIBERATE NON-GOAL: this does NOT remap HTTP 401/403 onto
 * [AppState.ErrorKind.TokensInvalid]. TokensInvalid drives a re-pair in
 * [app.ouie.screens.error.ErrorScreen] (3s auto-retry, "Device needs
 * re-pairing"), and a 401 on an UNAUTHENTICATED endpoint like
 * screens-pairing-request means the Supabase gateway started pre-verifying
 * JWTs (verify_jwt drifted to true), not that the device credentials went
 * bad. Sending that case into a re-pair loop would hide a server
 * misconfiguration behind a device-shaped symptom. Only the rendered
 * [FailureDiagnosis.detail] changes; control flow is untouched.
 */
object FailureClassifier {

    /**
     * @param operation the endpoint/step that failed, e.g. "pairing-request".
     *   Prefixed onto [FailureDiagnosis.detail] so the screen names the call
     *   that broke, not just the reason.
     */
    fun classify(operation: String, t: Throwable): FailureDiagnosis {
        val (kind, reason) = reasonFor(t)
        return FailureDiagnosis(kind = kind, detail = "$operation · $reason")
    }

    private fun reasonFor(t: Throwable): Pair<AppState.ErrorKind, String> = when (t) {
        // ── Transport: the request never reached a server ──────────────
        is UnknownHostException ->
            AppState.ErrorKind.NetworkUnavailable to "DNS lookup failed"
        is SocketTimeoutException ->
            AppState.ErrorKind.NetworkUnavailable to "connection timed out"
        is ConnectException ->
            AppState.ErrorKind.NetworkUnavailable to "connection refused"
        is NoRouteToHostException ->
            AppState.ErrorKind.NetworkUnavailable to "no route to host"

        // ── TLS: reached the host, refused to trust it. On a TV this is
        // overwhelmingly a wrong system clock (cert "not yet valid" /
        // "expired"), which is why the detail names date & time. Checked
        // BEFORE the generic IOException branch — SSLException extends it.
        is SSLException ->
            AppState.ErrorKind.SecureConnectionFailed to "TLS failed — check TV date & time"

        // ── The server answered, just not with success ─────────────────
        is HttpException ->
            AppState.ErrorKind.ServerUnavailable to "HTTP ${t.code()}"
        is RefreshFailedException ->
            AppState.ErrorKind.ServerUnavailable to "refresh HTTP ${t.httpCode}"
        is RecoveryFailedException ->
            AppState.ErrorKind.ServerUnavailable to "recover HTTP ${t.httpCode}"
        is PairingStatusFailedException ->
            AppState.ErrorKind.ServerUnavailable to
                if (t.httpCode == PairingRepository.EMPTY_BODY) "empty response body"
                else "HTTP ${t.httpCode}"
        is UnknownPairingStatusException ->
            AppState.ErrorKind.ServerUnavailable to "unknown status '${t.status}'"
        is SerializationException ->
            AppState.ErrorKind.ServerUnavailable to "malformed response"

        // ── Anything else I/O-shaped, then true unknowns ───────────────
        is IOException ->
            AppState.ErrorKind.NetworkUnavailable to (t.message?.take(60) ?: t.javaClass.simpleName)
        else ->
            AppState.ErrorKind.Unknown to t.javaClass.simpleName
    }
}
