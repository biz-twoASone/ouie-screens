// android-tv/app/src/main/java/app/ouie/screens/auth/TokenSource.kt
package app.ouie.screens.auth

/**
 * Read/write interface over token persistence. Production: TokenStore (EncryptedSharedPreferences).
 * Test: FakeTokenStore (in-memory).
 *
 * Identity-token lifecycle (mirror of POS PR #826):
 *   - Minted at pairing time, persisted via [saveIdentity].
 *   - Survives [clear] — the whole point: when refresh fails and would
 *     otherwise strand the TV, TokenAuthenticator presents the surviving
 *     identity token to /functions/screens-recover and re-issues a fresh
 *     access+refresh pair without dashboard intervention.
 *   - Only cleared by [clearAll] on explicit unpair.
 */
interface TokenSource {
    fun loadSync(): DeviceTokens?
    fun save(tokens: DeviceTokens)
    /** Clears access/refresh/screenId. PRESERVES the identity token. */
    fun clear()

    fun loadIdentitySync(): String?
    fun saveIdentity(identityToken: String)
    /** Clears everything, including the identity token. Use on explicit unpair only. */
    fun clearAll()
}
