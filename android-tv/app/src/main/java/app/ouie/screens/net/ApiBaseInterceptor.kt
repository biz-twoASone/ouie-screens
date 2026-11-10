// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseInterceptor.kt
package app.ouie.screens.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Moves every API request built against the compiled base onto the current
 * effective base. Retrofit instances keep the compiled base, so a switch takes
 * effect on the next request of every client (pairing, refresh/recover and the
 * authed config/heartbeat/cache-status client) without rebuilding them.
 * Requests to any other URL (R2 media, OTA APKs) pass through untouched.
 *
 * While a hint is adopted, a transport failure or a 5xx from the adopted host
 * forgets the hint and replays that call on the compiled base, so the next
 * calls stay there too. The next validated config offers the hint again and
 * [ApiBaseAdopter] re-adopts it only after probing it.
 */
class ApiBaseInterceptor(private val store: ApiBaseStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = store.current()
        val compiled = store.compiled.toString()
        val url = request.url.toString()
        if (target == store.compiled || !url.startsWith(compiled)) return chain.proceed(request)
        val moved = (target.toString() + url.substring(compiled.length)).toHttpUrl()
        val response = try {
            chain.proceed(request.newBuilder().url(moved).build())
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            return fallBack(chain, request, target)
        }
        if (response.code < 500) return response
        response.close()
        return fallBack(chain, request, target)
    }

    private fun fallBack(chain: Interceptor.Chain, request: Request, failed: HttpUrl): Response {
        if (store.current() == failed) {
            try {
                store.set(null)
            } catch (_: IOException) {
                // Not forgotten: the next call tries the adopted base again and lands here again.
            }
        }
        return chain.proceed(request)
    }
}
