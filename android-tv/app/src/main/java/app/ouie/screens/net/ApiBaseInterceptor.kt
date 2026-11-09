// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseInterceptor.kt
package app.ouie.screens.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Moves every API request built against the compiled base onto the current
 * effective base. Retrofit instances keep the compiled base, so a switch takes
 * effect on the next request of every client (pairing, refresh/recover and the
 * authed config/heartbeat/cache-status client) without rebuilding them.
 * Requests to any other URL (R2 media, OTA APKs) pass through untouched.
 */
class ApiBaseInterceptor(private val store: ApiBaseStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = store.current()
        val compiled = store.compiled.toString()
        val url = request.url.toString()
        if (target == store.compiled || !url.startsWith(compiled)) return chain.proceed(request)
        val moved = (target.toString() + url.substring(compiled.length)).toHttpUrl()
        return chain.proceed(request.newBuilder().url(moved).build())
    }
}
