// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseAdopter.kt
package app.ouie.screens.net

import app.ouie.screens.auth.TokenSource
import app.ouie.screens.errorbus.ErrorBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.io.IOException

/**
 * Applies the `api_base_url` hint carried by a validated config (200, or a
 * 304 confirming the last-good config) or a successful paired pickup.
 *
 *  - a well-formed HTTPS hint moves the screen to that base;
 *  - an absent hint moves it back to the compiled base (server unset);
 *  - a malformed hint is ignored: the current base stays.
 *
 * A move only happens after [probe] proves the target serves this screen's
 * config with the current access token, and is persisted before it takes
 * effect. A target that is down, mistyped or rejects the token is therefore
 * never adopted, and the probe cannot touch credentials, so pairing survives
 * every outcome. Failed attempts are retried on the next validated response
 * (one per config poll) and reported once per target to the [errorBus].
 */
class ApiBaseAdopter(
    private val store: ApiBaseStore,
    private val probe: ApiBaseProbe,
    private val tokens: TokenSource,
    private val errorBus: ErrorBus,
) {
    sealed interface Outcome {
        data object Unchanged : Outcome
        /** Malformed hint; the current base stays. */
        data object Ignored : Outcome
        /** No access token yet to validate the target with. */
        data object Deferred : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Switched(val base: HttpUrl) : Outcome
    }

    private val mutex = Mutex()
    private var lastReport: String? = null

    suspend fun reconcile(hint: String?): Outcome = mutex.withLock {
        val target = if (hint == null) {
            store.compiled
        } else {
            ApiBaseUrl.parseHint(hint) ?: run {
                reportOnce("api_base_invalid", "malformed api_base_url ignored")
                return@withLock Outcome.Ignored
            }
        }
        if (target == store.current()) return@withLock Outcome.Unchanged
        val accessToken = tokens.loadSync()?.accessToken ?: return@withLock Outcome.Deferred

        val failure = try {
            probe.check(target, accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            "probe ${t.javaClass.simpleName}"
        }
        if (failure != null) {
            reportOnce("api_base_probe_failed", "${target.host}: $failure")
            return@withLock Outcome.Rejected(failure)
        }
        try {
            store.set(target)
        } catch (e: IOException) {
            reportOnce("api_base_persist_failed", target.host)
            return@withLock Outcome.Rejected("persist failed")
        }
        lastReport = null
        Outcome.Switched(target)
    }

    private fun reportOnce(kind: String, message: String) {
        val key = "$kind|$message"
        if (key == lastReport) return
        lastReport = key
        errorBus.report(kind, null, message)
    }
}
