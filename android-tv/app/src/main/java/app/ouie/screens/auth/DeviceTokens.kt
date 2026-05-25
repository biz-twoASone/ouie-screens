package app.ouie.screens.auth

import kotlinx.serialization.Serializable

@Serializable
data class DeviceTokens(
    val accessToken: String,
    val refreshToken: String,
    val screenId: String,
    val expiresInSeconds: Int,
)
