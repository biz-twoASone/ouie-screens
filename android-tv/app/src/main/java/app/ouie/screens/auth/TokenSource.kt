// android-tv/app/src/main/java/app/ouie/screens/auth/TokenSource.kt
package app.ouie.screens.auth

/**
 * Read/write interface over token persistence. Production: TokenStore (EncryptedSharedPreferences).
 * Test: FakeTokenStore (in-memory).
 */
interface TokenSource {
    fun loadSync(): DeviceTokens?
    fun save(tokens: DeviceTokens)
    fun clear()
}
