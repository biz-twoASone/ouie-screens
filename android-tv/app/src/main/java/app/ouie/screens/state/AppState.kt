package app.ouie.screens.state

sealed interface AppState {
    data object Pairing : AppState
    data class Running(val screenId: String) : AppState
    data class Error(val kind: ErrorKind) : AppState

    enum class ErrorKind {
        NetworkUnavailable,
        ServerUnavailable,
        TokensInvalid,
        Unknown,
    }
}
