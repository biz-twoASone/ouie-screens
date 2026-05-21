package app.ouie.screens.net

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface DeviceApi {
    @POST("screens-refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<RefreshResponse>
}

@Serializable
data class RefreshBody(val refresh_token: String)

@Serializable
data class RefreshResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
)
