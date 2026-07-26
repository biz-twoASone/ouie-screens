package app.ouie.screens.state

sealed interface AppState {
    data object Pairing : AppState
    data class Running(val screenId: String) : AppState

    /**
     * @param detail concrete cause, e.g. "pairing-request · HTTP 401", produced
     *   by [app.ouie.screens.error.FailureClassifier]. Rendered on the error
     *   screen so a fault can be identified from a photo of the TV instead of
     *   an on-site `adb logcat`. Null when a caller has no better information
     *   than the kind itself.
     */
    data class Error(val kind: ErrorKind, val detail: String? = null) : AppState

    enum class ErrorKind {
        NetworkUnavailable,
        ServerUnavailable,

        /**
         * Host was reachable but the TLS handshake failed. Kept distinct from
         * [NetworkUnavailable] and [ServerUnavailable] because on Android TV
         * this is nearly always a wrong system clock, and neither "No network"
         * nor "Can't reach our server" would point anyone at the real fix.
         */
        SecureConnectionFailed,

        TokensInvalid,
        Unknown,
    }
}
