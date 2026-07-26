// android-tv/app/src/main/java/app/ouie/screens/net/RecoveryAdapter.kt
package app.ouie.screens.net

import app.ouie.screens.auth.DeviceTokens

/**
 * Identity-token recovery — sibling to [RefreshAdapter]. Called by
 * [TokenAuthenticator] as a last-ditch attempt before clearing the
 * token store and stranding the TV on the Pairing screen, AND by
 * [PairingViewModel] on cold boot when a surviving identity token can
 * unstick the TV before showing a pairing code at all.
 *
 * Implemented by [RetrofitRecoveryAdapter]. Test stubs in
 * `TokenAuthenticatorTest` use an in-memory implementation.
 */
interface RecoveryAdapter {
    /**
     * @param identityToken the long-lived JWT proving "I was previously
     *   paired as screen X" (persisted in TokenStore across [clear]s).
     * @param screenId optional locally-known screen ID — used as-is when
     *   present (TokenAuthenticator's mid-session recovery has this
     *   from [DeviceTokens.screenId]). On cold-boot recovery (where the
     *   TV has wiped DeviceTokens but identity survives), pass null and
     *   the screen ID returned by the server is used.
     */
    suspend fun recover(identityToken: String, screenId: String?): DeviceTokens
}

class RetrofitRecoveryAdapter(
    private val deviceApi: DeviceApi,
) : RecoveryAdapter {
    override suspend fun recover(identityToken: String, screenId: String?): DeviceTokens {
        val resp = deviceApi.recover("Bearer $identityToken")
        if (!resp.isSuccessful) throw RecoveryFailedException(resp.code())
        val body = resp.body() ?: throw RecoveryFailedException(-1)
        val resolvedScreenId = screenId
            ?: body.screen_id
            ?: throw RecoveryFailedException(-2)
        return DeviceTokens(
            accessToken = body.access_token,
            refreshToken = body.refresh_token,
            screenId = resolvedScreenId,
            expiresInSeconds = body.expires_in,
        )
    }
}

class RecoveryFailedException(val httpCode: Int) : Exception("recover failed: $httpCode")
