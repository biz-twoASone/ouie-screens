// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseInterceptorTest.kt
package app.ouie.screens.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
