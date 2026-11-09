package app.ouie.screens.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import app.ouie.screens.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The base baked into this APK; used whenever no server hint has been adopted. */
    fun compiledBaseUrl(): HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    /**
     * Shared OkHttp used by both pairing (no auth) and device (auth-required) retrofits.
     * Caller layers an Authenticator on top for device calls (Task 3.4).
     *
     * API clients pass [apiBase] so their requests follow the adopted base;
     * its rewrite runs first so logs show the URL actually called. Clients for
     * absolute URLs (media, OTA, the base probe) pass nothing.
     */
    fun baseHttpClient(apiBase: ApiBaseStore? = null): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.BASIC
            // Redact auth header even in BODY-level logs.
            redactHeader("Authorization")
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        if (apiBase != null) builder.addInterceptor(ApiBaseInterceptor(apiBase))
        return builder.addInterceptor(logging)
    }

    fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(compiledBaseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
