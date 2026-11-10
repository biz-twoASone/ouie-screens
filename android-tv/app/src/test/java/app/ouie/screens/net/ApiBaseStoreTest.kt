// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseStoreTest.kt
package app.ouie.screens.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ApiBaseStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val compiled = "https://legacy.example.invalid/functions/v1/".toHttpUrl()
    private val adopted = "https://screens.example.invalid/api/screens/v1/".toHttpUrl()

    private fun file() = File(tmp.root, "signage/api_base_url")

    @Test
    fun `no file means the compiled base`() {
        assertEquals(compiled, ApiBaseStore(file(), compiled).current())
    }

    @Test
    fun `an adopted base survives a process restart`() {
        ApiBaseStore(file(), compiled).set(adopted)
        assertEquals(adopted, ApiBaseStore(file(), compiled).current())
    }

    @Test
    fun `forgetting the hint returns to the compiled base across restarts`() {
        val store = ApiBaseStore(file(), compiled)
        store.set(adopted)
        store.set(null)
        assertEquals(compiled, store.current())
        assertFalse(file().exists())
        assertEquals(compiled, ApiBaseStore(file(), compiled).current())
    }

    @Test
    fun `a corrupt or non-HTTPS stored value falls back to the compiled base`() {
        file().parentFile!!.mkdirs()
        for (stored in listOf("garbage", "http://screens.example.invalid/api/screens/v1/", "")) {
            file().writeText(stored)
            assertEquals("stored <$stored>", compiled, ApiBaseStore(file(), compiled).current())
        }
    }

    @Test
    fun `a write that cannot be made durable leaves the current base unchanged`() {
        // Parent "directory" is a regular file, so nothing can be written under it.
        val blocked = File(tmp.newFile("not-a-dir"), "api_base_url")
        val store = ApiBaseStore(blocked, compiled)
        assertThrows(IOException::class.java) { store.set(adopted) }
        assertEquals(compiled, store.current())
    }
}
