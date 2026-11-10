// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseUrl.kt
package app.ouie.screens.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server-advertised screens API base (`api_base_url`, ouie S5c). The
 * server only ever emits `https://<canonical origin>/api/screens/v1/`, so any
 * other shape is malformed and is ignored, never repaired.
 */
object ApiBaseUrl {
    const val PATH = "/api/screens/v1/"

    /** Returns the hint as a Retrofit-style base, or null when it is not one the server could have sent. */
    fun parseHint(raw: String): HttpUrl? {
        // OkHttp's parser silently trims whitespace and folds backslashes; reject instead.
        if (raw.any { it <= ' ' || it == '\u007f' || it == '\\' }) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()) return null
        if (url.encodedQuery != null || url.encodedFragment != null) return null
        if (url.encodedPath != PATH) return null
        // OkHttp canonicalises (lower-case/punycode host, drops :443 and an
        // empty "@"); the server emits the canonical form, so any difference
        // means the value was not produced by the server's resolver.
        return if (url.toString() == raw) url else null
    }
}
