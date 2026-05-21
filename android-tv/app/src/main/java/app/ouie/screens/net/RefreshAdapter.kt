// android-tv/app/src/main/java/app/ouie/screens/net/RefreshAdapter.kt
package app.ouie.screens.net

import app.ouie.screens.auth.DeviceTokens

/**
 * Abstraction over "do a refresh call against the server". Factored out so
 * TokenAuthenticator doesn't depend on a Retrofit interface directly —
 * easier to test and easier to swap the transport layer later.
 */
interface RefreshAdapter {
    suspend fun refresh(current: DeviceTokens): DeviceTokens
}
