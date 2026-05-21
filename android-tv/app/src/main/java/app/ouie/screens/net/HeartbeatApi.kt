// android-tv/app/src/main/java/app/ouie/screens/net/HeartbeatApi.kt
package app.ouie.screens.net

import app.ouie.screens.heartbeat.HeartbeatPayload
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface HeartbeatApi {
    /**
     * POST `/devices-heartbeat`. Server returns 204 on success. We keep Response<Unit>
     * rather than a suspend Unit so callers can inspect `.code()` if we ever want
     * to differentiate 204 from rare 400 responses without exception handling.
     */
    @POST("screens-heartbeat")
    suspend fun post(@Body body: HeartbeatPayload): Response<Unit>
}
