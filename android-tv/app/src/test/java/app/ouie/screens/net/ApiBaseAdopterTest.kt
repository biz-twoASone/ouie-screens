// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseAdopterTest.kt
package app.ouie.screens.net

import app.ouie.screens.auth.DeviceTokens
import app.ouie.screens.errorbus.ErrorBus
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiBaseAdopterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val compiled = "https://legacy.example.invalid/functions/v1/".toHttpUrl()
    private val hint = "https://screens.example.invalid/api/screens/v1/"
    private val paired = DeviceTokens("access-1", "refresh-1", "screen-1", 3600)

    private val probed = mutableListOf<Pair<HttpUrl, String>>()
    private val errorBus = ErrorBus()

    private fun adopter(
        store: ApiBaseStore = ApiBaseStore(File(tmp.root, "api_base_url"), compiled),
        tokens: RecordingTokenSource = RecordingTokenSource(paired),
        probe: ApiBaseProbe = ApiBaseProbe { base, token -> probed += base to token; null },
    ) = ApiBaseAdopter(store, probe, tokens, errorBus)

    @Test
    fun `without an access token the switch is deferred, not attempted`() = runBlocking {
        val store = ApiBaseStore(File(tmp.root, "api_base_url"), compiled)
        val outcome = adopter(store = store, tokens = RecordingTokenSource(null)).reconcile(hint)
        assertEquals(ApiBaseAdopter.Outcome.Deferred, outcome)
        assertTrue(probed.isEmpty())
        assertEquals(compiled, store.current())
    }

    @Test
    fun `the probe is given the persisted access token`() = runBlocking {
        adopter().reconcile(hint)
        assertEquals(listOf(hint.toHttpUrl() to "access-1"), probed)
    }

    @Test
    fun `a crashing probe is a rejection, never an escape into the caller`() = runBlocking {
        val store = ApiBaseStore(File(tmp.root, "api_base_url"), compiled)
        val outcome = adopter(store = store, probe = { _, _ -> throw IllegalStateException("boom") }).reconcile(hint)
        assertTrue(outcome is ApiBaseAdopter.Outcome.Rejected)
        assertEquals(compiled, store.current())
    }

    @Test
    fun `a base that cannot be persisted is not adopted`() = runBlocking {
        val store = ApiBaseStore(File(tmp.newFile("not-a-dir"), "api_base_url"), compiled)
        val outcome = adopter(store = store).reconcile(hint)
        assertEquals(ApiBaseAdopter.Outcome.Rejected("persist failed"), outcome)
        assertEquals(compiled, store.current())
        assertEquals(listOf("api_base_persist_failed"), errorBus.drain().map { it.kind })
    }

    @Test
    fun `a repeating failure is reported once and again after it changes`() = runBlocking {
        var answer: String? = "HTTP 503"
        val a = adopter(probe = { _, _ -> answer })
        a.reconcile(hint)
        a.reconcile(hint)
        a.reconcile("http://screens.example.invalid/api/screens/v1/")
        answer = "HTTP 401"
        a.reconcile(hint)
        val events = errorBus.drain()
        assertEquals(
            listOf("api_base_probe_failed", "api_base_invalid", "api_base_probe_failed"),
            events.map { it.kind },
        )
        assertEquals("screens.example.invalid: HTTP 503", events[0].message)
        // The raw malformed value is never echoed into the error report.
        assertEquals("malformed api_base_url ignored", events[1].message)
    }
}
