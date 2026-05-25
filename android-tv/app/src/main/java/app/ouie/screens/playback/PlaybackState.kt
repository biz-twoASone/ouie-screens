// android-tv/app/src/main/java/app/ouie/screens/playback/PlaybackState.kt
package app.ouie.screens.playback

sealed interface PlaybackState {
    /** Config not yet fetched — initial sync in progress. */
    data object Syncing : PlaybackState

    /** Config fetched but no schedule rule resolves a playlist for this screen. */
    data object NoPlaylist : PlaybackState

    /** A resolved playlist exists but has zero items. */
    data object EmptyPlaylist : PlaybackState

    /**
     * A playlist is desired but not fully cached yet. We're either starting fresh
     * or a schedule just flipped. Show "Preparing content…" to avoid a customer-
     * facing error. Spec §6.3: never interrupt an already-playing cached playlist
     * for this.
     */
    data object Preparing : PlaybackState

    /** Playing an item from a cached playlist. */
    data class Playing(
        val playlistId: String,
        val index: Int,
        val item: PlaybackItem,
        val generation: Long,
    ) : PlaybackState
}
