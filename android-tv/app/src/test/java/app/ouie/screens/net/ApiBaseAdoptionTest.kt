// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseAdoptionTest.kt
package app.ouie.screens.net

import app.ouie.screens.auth.DeviceTokens
import app.ouie.screens.config.ConfigRepository
import app.ouie.screens.config.ConfigStore
import app.ouie.screens.errorbus.ErrorBus
import app.ouie.screens.pairing.PairingRepository
import app.ouie.screens.pairing.PairingViewModel
import app.ouie.screens.state.AppState
import app.ouie.screens.state.AppStateHolder
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JVM end-to-end proof of api_base_url adoption and fallback: real OkHttp
 * clients built by [ApiClient.baseHttpClient], real Retrofit APIs, the real
 * [TokenAuthenticator], [ConfigRepository], [ApiBaseStore] and
 * [HttpApiBaseProbe], talking HTTPS to local fake ouie hosts. No real host is
 * contacted: every Retrofit base is a local MockWebServer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiBaseAdoptionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val cert = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
        .build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()

    /** A fake ouie host serving the screens endpoints under [basePath]. */
    private inner class Host(private val basePath: String) {
        val server = MockWebServer()
        @Volatile var hint: String? = null
        @Volatile var accepted: Set<String> = setOf("access-1")
        @Volatile var pickupHint: String? = null
        val calls = CopyOnWriteArrayList<RecordedRequest>()

        init {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls += request
                    val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    return when (endpoint(request)) {
                        "screens-config" -> if (bearer in accepted) config(request) else MockResponse().setResponseCode(401)
                        "screens-cache-status" -> MockResponse().setResponseCode(if (bearer in accepted) 204 else 401)
                        "screens-refresh" -> MockResponse().setBody(
                            """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}""",
                        )
                        "screens-pairing-request" -> MockResponse().setBody(
                            """{"code":"ABC234","expires_at":"2099-01-01T00:00:00Z"}""",
                        )
                        "screens-pairing-status" -> MockResponse().setBody(
                            """{"status":"paired","screen_id":"screen-1","access_token":"access-1",""" +
                                """"refresh_token":"refresh-1","expires_in":3600,"screen_identity_token":"identity-1"""" +
                                (pickupHint?.let { ""","api_base_url":"$it"""" } ?: "") + "}",
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun base(): HttpUrl = server.url(basePath)

        private fun endpoint(request: RecordedRequest): String? =
            request.requestUrl?.encodedPath?.takeIf { it.startsWith(basePath) }?.removePrefix(basePath)

        private fun config(request: RecordedRequest): MockResponse {
            val etag = "\"v-${hint.hashCode()}\""
            if (request.getHeader("If-None-Match") == etag) return MockResponse().setResponseCode(304).setHeader("ETag", etag)
            val hintField = hint?.let { ""","api_base_url":"$it"""" } ?: ""
            return MockResponse().setHeader("ETag", etag).setBody(
                """{"version":"v1","device":{"id":"screen-1","store_id":"s1","timezone":"Asia/Jakarta"}$hintField}""",
            )
        }

        fun hits(endpoint: String) = calls.count { endpoint(it) == endpoint }
    }

    private lateinit var compiled: Host
    private lateinit var next: Host
    private lateinit var tokens: RecordingTokenSource
    private lateinit var store: ApiBaseStore
    private lateinit var errorBus: ErrorBus
    private lateinit var adopter: ApiBaseAdopter
    private lateinit var config: ConfigRepository
    private lateinit var cacheStatus: CacheStatusApi
    private val extraHosts = mutableListOf<Host>()

    private fun OkHttpClient.Builder.trusting() = sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(compiled.base())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private fun storeFile() = File(tmp.root, "signage/api_base_url")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        compiled = Host("/functions/v1/")
        next = Host(ApiBaseUrl.PATH)
        tokens = RecordingTokenSource(DeviceTokens("access-1", "refresh-1", "screen-1", 3600), identity = "identity-1")
        store = ApiBaseStore(storeFile(), compiled.base())
        errorBus = ErrorBus()
        // Same shape as the production DI graph in AppModule.
        val probeClient = ApiClient.baseHttpClient().followRedirects(false).followSslRedirects(false).trusting().build()
        adopter = ApiBaseAdopter(store, HttpApiBaseProbe(probeClient, json), tokens, errorBus)
        val deviceApi = retrofit(ApiClient.baseHttpClient(store).trusting().build()).create(DeviceApi::class.java)
        val authed = ApiClient.baseHttpClient(store)
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, RetrofitRefreshAdapter(deviceApi), RetrofitRecoveryAdapter(deviceApi)))
            .trusting()
            .build()
        config = ConfigRepository(retrofit(authed).create(ConfigApi::class.java), ConfigStore(File(tmp.root, "signage/config"), json), adopter)
        cacheStatus = retrofit(authed).create(CacheStatusApi::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        (listOf(compiled, next) + extraHosts).forEach { it.server.shutdown() }
    }

    private fun host(basePath: String = ApiBaseUrl.PATH) = Host(basePath).also { extraHosts += it }

    @Test
    fun `a validated config hint moves every client to the new base and keeps the pairing`() = runBlocking {
        compiled.hint = next.base().toString()
        next.hint = next.base().toString()

        assertTrue(config.fetch() is ConfigRepository.Result.Applied)

        assertEquals(next.base(), store.current())
        assertEquals("the target was probed before the switch", 1, next.hits("screens-config"))
        assertTrue(cacheStatus.post(CacheStatusBatch(emptyList())).isSuccessful)
        assertEquals(1, next.hits("screens-cache-status"))
        assertEquals(0, compiled.hits("screens-cache-status"))
        assertEquals(ConfigRepository.Result.NotModified, config.fetch())
        assertEquals("config polls follow the switch", 2, next.hits("screens-config"))
        assertEquals(1, compiled.hits("screens-config"))

        assertEquals(emptyList<String>(), tokens.writes)
        assertEquals("access-1", tokens.loadSync()?.accessToken)
        assertEquals("identity-1", tokens.loadIdentitySync())
        assertEquals("survives a restart", next.base(), ApiBaseStore(storeFile(), compiled.base()).current())
    }

    @Test
    fun `token refresh after the switch goes to the adopted base`() = runBlocking {
        compiled.hint = next.base().toString()
        config.fetch()
        assertEquals(next.base(), store.current())

        next.accepted = setOf("access-2") // the old access token has expired
        assertTrue(cacheStatus.post(CacheStatusBatch(emptyList())).isSuccessful)

        assertEquals(1, next.hits("screens-refresh"))
        assertEquals(0, compiled.hits("screens-refresh"))
        assertEquals("access-2", tokens.loadSync()?.accessToken)
        assertFalse(tokens.writes.contains("clear"))
    }

    @Test
    fun `an unset hint returns the screen to the compiled base`() = runBlocking {
        store.set(next.base())
        next.hint = null

        assertTrue(config.fetch() is ConfigRepository.Result.Applied)

        assertEquals(compiled.base(), store.current())
        assertFalse(storeFile().exists())
        assertEquals("the compiled base was probed before the switch", 1, compiled.hits("screens-config"))
        cacheStatus.post(CacheStatusBatch(emptyList()))
        assertEquals(1, compiled.hits("screens-cache-status"))
        assertEquals(emptyList<String>(), tokens.writes)
    }

    @Test
    fun `a target that rejects the screen is never adopted, never touches pairing, and is retried on 304`() = runBlocking {
        val other = host()
        other.accepted = emptySet()
        compiled.hint = other.base().toString()

        config.fetch()
        assertEquals(compiled.base(), store.current())
        assertEquals(ConfigRepository.Result.NotModified, config.fetch())
        assertEquals("retried on the 304 that confirms the hint", 2, other.hits("screens-config"))
        assertEquals(compiled.base(), store.current())
        assertEquals("the probe's 401 must not refresh, recover or clear", emptyList<String>(), tokens.writes)
        assertEquals(0, compiled.hits("screens-refresh"))
        assertEquals(listOf("api_base_probe_failed"), errorBus.drain().map { it.kind })

        other.accepted = setOf("access-1")
        assertEquals(ConfigRepository.Result.NotModified, config.fetch())
        assertEquals(other.base(), store.current())
    }

    @Test
    fun `an unreachable target is not adopted`() = runBlocking {
        val gone = Host(ApiBaseUrl.PATH)
        val deadBase = gone.base()
        gone.server.shutdown()
        compiled.hint = deadBase.toString()

        config.fetch()

        assertEquals(compiled.base(), store.current())
        assertEquals(emptyList<String>(), tokens.writes)
    }

    @Test
    fun `a malformed hint is ignored and the current base stays`() = runBlocking {
        compiled.hint = next.base().newBuilder().scheme("http").build().toString()
        config.fetch()
        assertEquals(compiled.base(), store.current())
        assertEquals(0, next.hits("screens-config"))
        assertEquals("rejected as malformed, not merely unreachable", listOf("api_base_invalid"), errorBus.drain().map { it.kind })

        // Malformed is not "unset": an adopted screen does not fall back on it.
        store.set(next.base())
        next.hint = "https://@" + next.base().toString().removePrefix("https://")
        config.fetch()
        assertEquals(next.base(), store.current())
        assertEquals("no probe of the compiled base", 1, compiled.hits("screens-config"))
    }

    @Test
    fun `an ETag saved before the hint existed is not replayed`() = runBlocking {
        compiled.hint = next.base().toString()
        val configDir = File(tmp.root, "signage/config").apply { mkdirs() }
        // What a pre-hint build left behind: a 304-able ETag for a payload whose hint it dropped.
        File(configDir, "config.etag").writeText("\"v-${compiled.hint.hashCode()}\"")
        val upgraded = ConfigRepository(
            retrofit(ApiClient.baseHttpClient(store).addInterceptor(AuthInterceptor(tokens)).trusting().build())
                .create(ConfigApi::class.java),
            ConfigStore(configDir, json),
            adopter,
        )

        assertTrue(upgraded.fetch() is ConfigRepository.Result.Applied)
        assertNull(compiled.calls.first().getHeader("If-None-Match"))
        assertEquals(next.base(), store.current())
    }

    private fun pairFresh(): AppStateHolder {
        tokens.clearAll()
        tokens.writes.clear()
        val appState = AppStateHolder()
        PairingViewModel(
            repo = PairingRepository(
                api = retrofit(ApiClient.baseHttpClient(store).trusting().build()).create(PairingApi::class.java),
                proposedName = "test-tv",
                pollIntervalMs = 1,
            ),
            tokenStore = tokens,
            recoveryAdapter = RetrofitRecoveryAdapter(
                retrofit(ApiClient.baseHttpClient(store).trusting().build()).create(DeviceApi::class.java),
            ),
            appState = appState,
            apiBase = adopter,
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (appState.state.value !is AppState.Running && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return appState
    }

    @Test
    fun `a paired pickup hint is adopted with the persisted pickup tokens`() {
        compiled.pickupHint = next.base().toString()

        val appState = pairFresh()

        assertEquals(AppState.Running("screen-1"), appState.state.value)
        assertEquals(next.base(), store.current())
        assertEquals("Bearer access-1", next.calls.single().getHeader("Authorization"))
        assertEquals(listOf("save", "saveIdentity"), tokens.writes)
    }

    @Test
    fun `a pickup hint whose target fails leaves the fresh pairing on the base that paired`() {
        val other = host()
        other.accepted = emptySet()
        compiled.pickupHint = other.base().toString()

        val appState = pairFresh()

        assertEquals(AppState.Running("screen-1"), appState.state.value)
        assertEquals(compiled.base(), store.current())
        assertEquals("access-1", tokens.loadSync()?.accessToken)
        assertEquals("identity-1", tokens.loadIdentitySync())
    }
}
