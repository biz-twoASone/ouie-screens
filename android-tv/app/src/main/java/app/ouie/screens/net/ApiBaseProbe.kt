// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseProbe.kt
package app.ouie.screens.net

import app.ouie.screens.config.ConfigDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Proves a candidate base serves this screen before the app switches to it. */
fun interface ApiBaseProbe {
    /**
     * @return null when [base] answered `screens-config` for [accessToken]
     *   with 200 and a body that parses as a config; otherwise a short,
     *   value-free reason (never the token or the response body).
     */
    suspend fun check(base: HttpUrl, accessToken: String): String?
}

/**
 * [client] MUST have no [ApiBaseInterceptor] (it would move the probe of the
 * compiled base back onto the adopted one), no authenticator (a 401 from a
 * candidate must never refresh, recover or clear this screen's tokens) and
 * redirects disabled (a candidate must answer for itself).
 */
class HttpApiBaseProbe(
    private val client: OkHttpClient,
    private val json: Json,
) : ApiBaseProbe {
    override suspend fun check(base: HttpUrl, accessToken: String): String? = withContext(Dispatchers.IO) {
        val url = base.resolve("screens-config") ?: return@withContext "unresolvable base"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) return@withContext "HTTP ${resp.code}"
                val body = resp.body?.string() ?: return@withContext "empty body"
                json.decodeFromString(ConfigDto.serializer(), body)
                null
            }
        } catch (e: IOException) {
            "network ${e.javaClass.simpleName}"
        } catch (_: IllegalArgumentException) {
            // kotlinx SerializationException is an IllegalArgumentException.
            "invalid config body"
        }
    }
}
