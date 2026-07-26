package app.ouie.screens.net

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DeviceApi {
    @POST("screens-refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<RefreshResponse>

    /**
     * Identity-token recovery. The TV presents its long-lived
     * screen_identity_token (persisted across [TokenSource.clear]) as
     * Bearer auth; server rotates the refresh token + mints a new
     * access token, gated on screens.revoked_at IS NULL. Mirror of POS
     * /api/pos/devices/auth/recover (PR #826).
     *
     * 401 on any failure (missing/invalid token, screen deleted, screen
     * revoked) — caller falls back to clearing and stranding on the
     * Pairing screen.
     */
    @POST("screens-recover")
    suspend fun recover(@Header("Authorization") bearer: String): Response<RefreshResponse>
}

@Serializable
data class RefreshBody(val refresh_token: String)

@Serializable
data class RefreshResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    /**
     * Set by /functions/screens-recover so the cold-boot recovery path
     * (TokenStore.loadSync() returned null but identity token is present)
     * can rebuild DeviceTokens without decoding the JWT client-side.
     * Refresh responses leave it null — the caller already has it.
     */
    val screen_id: String? = null,
)
