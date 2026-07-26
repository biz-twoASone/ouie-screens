package app.ouie.screens.pairing

import app.ouie.screens.auth.DeviceTokens
import app.ouie.screens.net.PairingApi
import app.ouie.screens.net.PairingRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Owns the two pairing endpoints. UI observes a suspending `observeClaim`
 * which polls until it gets a terminal status, then returns a `ClaimResult`.
 *
 * The `proposedName` is a human-readable hint the operator sees on the
 * dashboard claim form ("TV-kitchen-1"). In 3a we pass the Android Build.MODEL
 * by default; operators can rename post-claim from the dashboard.
 */
class PairingRepository(
    private val api: PairingApi,
    private val proposedName: String,
    private val pollIntervalMs: Long = 3_000,
) {
    data class PairingCode(val code: String, val expiresAtIso: String)

    /**
     * Pickup bundle drained from screens-pairing-status on the first
     * paired-read. Bundles the short-lived auth tokens with the
     * long-lived screen-identity token (null for TVs paired before the
     * identity-recovery feature shipped on 2026-05-28; the next pair /
     * re-pair will mint one for them).
     */
    data class ClaimPickup(
        val tokens: DeviceTokens,
        val identityToken: String?,
    )

    sealed interface ClaimResult {
        data class Paired(val pickup: ClaimPickup) : ClaimResult
        data object Pending : ClaimResult         // never returned — observeClaim loops on Pending
        data object Expired : ClaimResult
        data object PickupConsumed : ClaimResult  // re-pair
        data class Error(val cause: Throwable) : ClaimResult
    }

    suspend fun requestCode(): PairingCode {
        val resp = api.requestCode(PairingRequestBody(device_proposed_name = proposedName))
        return PairingCode(resp.code, resp.expires_at)
    }

    /**
     * Polls `/pairing-status` every `pollIntervalMs` until a terminal state.
     * Caller is responsible for also tracking the 15-min TTL; we don't
     * preemptively expire since the server will flip status to "expired"
     * within one poll interval of the TTL.
     */
    suspend fun observeClaim(code: String): ClaimResult {
        while (true) {
            val resp = try {
                api.status(code)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return ClaimResult.Error(t)
            }
            if (!resp.isSuccessful) {
                return ClaimResult.Error(RuntimeException("pairing-status HTTP ${resp.code()}"))
            }
            val body = resp.body() ?: return ClaimResult.Error(RuntimeException("empty body"))

            when (body.status) {
                "pending" -> delay(pollIntervalMs)
                "expired" -> return ClaimResult.Expired
                "paired" -> {
                    val at = body.access_token ?: return ClaimResult.PickupConsumed
                    val rt = body.refresh_token ?: return ClaimResult.PickupConsumed
                    val sid = body.screen_id ?: return ClaimResult.PickupConsumed
                    val exp = body.expires_in ?: 3600
                    return ClaimResult.Paired(
                        ClaimPickup(
                            tokens = DeviceTokens(at, rt, sid, exp),
                            identityToken = body.screen_identity_token,
                        ),
                    )
                }
                "paired_pickup_consumed" -> return ClaimResult.PickupConsumed
                else -> return ClaimResult.Error(RuntimeException("unknown status: ${body.status}"))
            }
        }
    }
}
