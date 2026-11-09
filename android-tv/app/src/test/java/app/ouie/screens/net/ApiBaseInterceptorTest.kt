// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseInterceptorTest.kt
package app.ouie.screens.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiBaseInterceptorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val compiled = MockWebServer().apply { start() }
    private val adopted = MockWebServer().apply { start() }
    private val unrelated = MockWebServer().apply { start() }

    @After
    fun tearDown() = listOf(compiled, adopted, unrelated).forEach { it.shutdown() }

    private val store by lazy { ApiBaseStore(File(tmp.root, "api_base_url"), compiled.url("/functions/v1/")) }
    private val client by lazy { OkHttpClient.Builder().addInterceptor(ApiBaseInterceptor(store)).build() }

    private fun get(url: okhttp3.HttpUrl) =
        client.newCall(Request.Builder().url(url).build()).execute().close()

    @Test
    fun `API requests move to the adopted base with path and query intact`() {
        store.set(adopted.url("/api/screens/v1/"))
        adopted.enqueue(MockResponse())

        get(compiled.url("/functions/v1/screens-pairing-status?code=ABC234"))

        assertEquals("/api/screens/v1/screens-pairing-status?code=ABC234", adopted.takeRequest().path)
        assertEquals(0, compiled.requestCount)
    }

    @Test
    fun `media and OTA URLs are never moved`() {
        store.set(adopted.url("/api/screens/v1/"))
        unrelated.enqueue(MockResponse())
        compiled.enqueue(MockResponse())

        get(unrelated.url("/media/abc.mp4?X-Amz-Signature=s"))
        // Same host as the compiled base but outside its path.
        get(compiled.url("/storage/v1/object/x.apk"))

        assertEquals("/media/abc.mp4?X-Amz-Signature=s", unrelated.takeRequest().path)
        assertEquals("/storage/v1/object/x.apk", compiled.takeRequest().path)
        assertEquals(0, adopted.requestCount)
    }

    @Test
    fun `without an adopted base requests stay on the compiled base`() {
        compiled.enqueue(MockResponse())
        get(compiled.url("/functions/v1/screens-config"))
        assertEquals("/functions/v1/screens-config", compiled.takeRequest().path)
    }

    private val adoptedBase get() = adopted.url("/api/screens/v1/")
    private val adoptedFile get() = File(tmp.root, "api_base_url")

    @Test
    fun `a connect failure on the adopted base replays the call on the compiled base and stays there`() {
        store.set(adoptedBase)
        adopted.shutdown()
        compiled.enqueue(MockResponse().setBody("first"))
        compiled.enqueue(MockResponse().setBody("second"))

        get(compiled.url("/functions/v1/screens-config"))
        get(compiled.url("/functions/v1/screens-heartbeat"))

        assertEquals("/functions/v1/screens-config", compiled.takeRequest().path)
        assertEquals("/functions/v1/screens-heartbeat", compiled.takeRequest().path)
        assertEquals(compiled.url("/functions/v1/"), store.current())
        assertFalse(adoptedFile.exists())
    }

    @Test
    fun `a 5xx from the adopted base replays the call on the compiled base`() {
        store.set(adoptedBase)
        adopted.enqueue(MockResponse().setResponseCode(503))
        compiled.enqueue(MockResponse().setBody("ok"))

        val code = client.newCall(Request.Builder().url(compiled.url("/functions/v1/screens-config")).build())
            .execute().use { it.code }

        assertEquals(200, code)
        assertEquals(1, adopted.requestCount)
        assertEquals(compiled.url("/functions/v1/"), store.current())
    }

    @Test
    fun `a connection dropped mid-call on the adopted base falls back too`() {
        store.set(adoptedBase)
        adopted.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        adopted.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        compiled.enqueue(MockResponse())

        get(compiled.url("/functions/v1/screens-config"))

        assertEquals(1, compiled.requestCount)
        assertEquals(compiled.url("/functions/v1/"), store.current())
    }

    @Test
    fun `a 4xx from the adopted base is the answer and does not forget the base`() {
        store.set(adoptedBase)
        adopted.enqueue(MockResponse().setResponseCode(404))

        val code = client.newCall(Request.Builder().url(compiled.url("/functions/v1/screens-config")).build())
            .execute().use { it.code }

        assertEquals(404, code)
        assertEquals(0, compiled.requestCount)
        assertEquals(adoptedBase, store.current())
    }

    @Test
    fun `when the compiled base also fails its answer is returned`() {
        store.set(adoptedBase)
        adopted.enqueue(MockResponse().setResponseCode(502))
        compiled.enqueue(MockResponse().setResponseCode(500))

        val code = client.newCall(Request.Builder().url(compiled.url("/functions/v1/screens-config")).build())
            .execute().use { it.code }

        assertEquals(500, code)
    }

    @Test
    fun `a failing media URL never forgets the adopted base`() {
        store.set(adoptedBase)
        unrelated.enqueue(MockResponse().setResponseCode(503))

        get(unrelated.url("/media/abc.mp4"))

        assertEquals(adoptedBase, store.current())
    }
}
