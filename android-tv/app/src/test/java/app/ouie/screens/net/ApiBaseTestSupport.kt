// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseTestSupport.kt
package app.ouie.screens.net

import app.ouie.screens.auth.DeviceTokens
import app.ouie.screens.auth.TokenSource
import app.ouie.screens.errorbus.ErrorBus
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.nio.file.Files

/** In-memory TokenSource that records every write, so tests can prove pairing was never touched. */
internal class RecordingTokenSource(initial: DeviceTokens?, identity: String? = null) : TokenSource {
    @Volatile private var tokens: DeviceTokens? = initial
    @Volatile private var identity: String? = identity
    val writes = mutableListOf<String>()

    override fun loadSync(): DeviceTokens? = tokens
    override fun save(tokens: DeviceTokens) { writes += "save"; this.tokens = tokens }
    override fun clear() { writes += "clear"; tokens = null }
    override fun loadIdentitySync(): String? = identity
    override fun saveIdentity(identityToken: String) { writes += "saveIdentity"; identity = identityToken }
    override fun clearAll() { writes += "clearAll"; tokens = null; identity = null }
}

/**
 * Adopter for tests that do not exercise adoption: it starts on the compiled
 * base and, with no tokens, can never switch.
 */
internal fun inertApiBaseAdopter(): ApiBaseAdopter {
    val dir = Files.createTempDirectory("api-base").toFile()
    Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    return ApiBaseAdopter(
        store = ApiBaseStore(File(dir, "api_base_url"), "https://compiled.example.invalid/functions/v1/".toHttpUrl()),
        probe = { _, _ -> "probe not expected" },
        tokens = RecordingTokenSource(null),
        errorBus = ErrorBus(),
    )
}
