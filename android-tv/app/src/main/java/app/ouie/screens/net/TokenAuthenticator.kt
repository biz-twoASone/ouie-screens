// android-tv/app/src/main/java/app/ouie/screens/net/TokenAuthenticator.kt
package app.ouie.screens.net

import app.ouie.screens.auth.TokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Invoked by OkHttp when a 401 happens. Runs the refresh flow under a mutex so
 * concurrent 401s share one refresh round-trip.
 *
 * Failure cascade (3 layers):
 *   1. Try refresh via [refreshAdapter]. Success → use new access token.
 *   2. Refresh failed AND we have a persisted identity token →
 *      try identity recovery via [recoveryAdapter]. Success → use new tokens.
 *   3. Both failed → clear short-lived tokens (identity preserved) and
 *      return null. OkHttp surfaces the 401 to the caller. App state
 *      transitions to Pairing on next boot; identity token survives so a
 *      future successful recovery (e.g. when the network heals) won't
 *      require dashboard re-pair.
 *
 * Why this cascade exists: PRIOR behavior was a single `catch (Throwable) →
 * tokenStore.clear() → null`. Any transient blip (network hiccup, brief
 * Supabase pause, CAS rotation race) was indistinguishable from real
 * revocation, so every TV that hit a refresh failure got stranded
 * permanently and re-paired into a duplicate row. The 2026-05-27 outage
 * (Bogor Tengah + Kanan, last-seen 24h+ ago, refusing to come back even
 * with cached playlist running) was this exact failure mode. POS got the
 * same fix in PR #826 (commit dcc696b0) — this is the screens mirror.
 *
 * Suspend bridge: OkHttp's Authenticator.authenticate() is blocking; we bridge
 * to the suspend refresh via runBlocking. The mutex prevents the classic
 * "two concurrent 401s = two refreshes" race (which would have invalidated
 * each other's refresh tokens via server-side CAS rotation).
 */
class TokenAuthenticator(
    private val tokenStore: TokenSource,
    private val refreshAdapter: RefreshAdapter,
    private val recoveryAdapter: RecoveryAdapter,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val current = tokenStore.loadSync()
        val requestAccess = response.request.header("Authorization")
            ?.removePrefix("Bearer ")?.trim()

        return runBlocking {
            mutex.withLock {
                val maybeRotated = tokenStore.loadSync()
                if (maybeRotated != null && maybeRotated.accessToken != requestAccess) {
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer ${maybeRotated.accessToken}")
                        .build()
                }

                // Step 1 — try refresh if we still have a refresh token.
                if (current != null) {
                    try {
                        val next = refreshAdapter.refresh(current)
                        tokenStore.save(next)
                        return@withLock response.request.newBuilder()
                            .header("Authorization", "Bearer ${next.accessToken}")
                            .build()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Fall through to identity recovery.
                    }
                }

                // Step 2 — identity recovery. Persisted identity token
                // survives [TokenStore.clear]; this is the recovery the
                // POS PR #826 fix added (we mirror it here for screens).
                // screenId comes from the live tokens when available; if
                // not (e.g., tokens already cleared by an earlier round),
                // the recovery adapter accepts null and falls back to
                // the screen_id the server returns.
                val identityToken = tokenStore.loadIdentitySync()
                if (identityToken != null) {
                    try {
                        val recovered = recoveryAdapter.recover(identityToken, current?.screenId)
                        tokenStore.save(recovered)
                        return@withLock response.request.newBuilder()
                            .header("Authorization", "Bearer ${recovered.accessToken}")
                            .build()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Fall through to clear+strand.
                    }
                }

                // Step 3 — both failed. Clear the short-lived tokens (keeping
                // the identity token if any, so a future network heal can
                // recover via the path above on the next OkHttp call).
                tokenStore.clear()
                null
            }
        }
    }
}
