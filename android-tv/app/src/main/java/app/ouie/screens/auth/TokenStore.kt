package app.ouie.screens.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** EncryptedSharedPreferences-backed token persistence. File "signage_tokens.xml" is
 *  excluded from Android auto-backup via res/xml/backup_rules.xml (device-keyed → backup is useless AND a leakage risk). */
class TokenStore(context: Context) : TokenSource {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "signage_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun loadSync(): DeviceTokens? {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val screenId = prefs.getString(KEY_SCREEN_ID, null) ?: return null
        val expiresIn = prefs.getInt(KEY_EXPIRES_IN, 3600)
        return DeviceTokens(access, refresh, screenId, expiresIn)
    }

    override fun save(tokens: DeviceTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putString(KEY_SCREEN_ID, tokens.screenId)
            .putInt(KEY_EXPIRES_IN, tokens.expiresInSeconds)
            .apply()
    }

    override fun clear() {
        // PRESERVES identity token — that's the whole point. TokenAuthenticator's
        // failed-refresh path used to call this and strand the TV; with identity
        // recovery wired, the survivor is what unsticks the TV via screens-recover.
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_SCREEN_ID)
            .remove(KEY_EXPIRES_IN)
            .apply()
    }

    override fun loadIdentitySync(): String? = prefs.getString(KEY_IDENTITY, null)

    override fun saveIdentity(identityToken: String) {
        prefs.edit().putString(KEY_IDENTITY, identityToken).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS = "access_token"
        const val KEY_SCREEN_ID = "screen_id"
        const val KEY_EXPIRES_IN = "expires_in"
        // Long-lived identity token (mirror of POS PR #826). Survives [clear].
        const val KEY_IDENTITY = "screen_identity_token"
    }
}
