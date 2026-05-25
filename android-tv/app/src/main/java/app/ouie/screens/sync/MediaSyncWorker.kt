// android-tv/app/src/main/java/app/ouie/screens/sync/MediaSyncWorker.kt
package app.ouie.screens.sync

import app.ouie.screens.cache.CacheLayout
import app.ouie.screens.cache.CacheManager
import app.ouie.screens.cache.MediaCacheIndex
import app.ouie.screens.config.ConfigDto
import app.ouie.screens.config.ConfigRepository
import app.ouie.screens.config.MediaDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SyncProgress(
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val totalBytes: Long = 0,
    val completedBytes: Long = 0,
    val lastError: String? = null,
    val failedItems: Int = 0,
)

/**
 * Serial download queue. Reads the current config + the cached set, downloads
 * anything missing one at a time, and writes the MediaCacheIndex row on success.
 * When a download fails, emits a cache_event with state=failed and backs off
 * before the next attempt.
 *
 * Triggers:
 *   - ConfigRepository.current emits a new version
 *   - CacheManager.cached changes (e.g., file disappeared)
 *
 * In 3b there is no explicit "sync window" gate: we always sync. This is safe
 * for v1's 8-device scale and matches spec §6.3's cache-before-switch
 * expectation that playback will re-trigger a sync if desired isn't cached.
 * Sync-window gating is deferred to v1.1 operational tuning.
 */
class MediaSyncWorker(
    private val scope: CoroutineScope,
    private val configRepo: ConfigRepository,
    private val cache: CacheManager,
    private val downloader: MediaDownloader,
    private val reporter: CacheStatusReporter,
    private val index: MediaCacheIndex,
) {

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // React to new configs AND to cache deletions. collectLatest cancels
            // the in-flight download loop when a newer signal arrives, which is
            // desirable — the newer config may no longer need that media.
            configRepo.current.collectLatest { cfg ->
                if (cfg == null) return@collectLatest
                syncAllMissing(cfg)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun syncAllMissing(cfg: ConfigDto) {
        val referenced = cfg.playlists.flatMap { pl -> pl.items.map { it.media_id } }.toSet()
        val cachedNow = cache.cached.value
        val missing = cfg.media.filter { it.id in referenced && it.id !in cachedNow }

        val totalBytes = missing.sumOf { it.size_bytes }
        _progress.value = SyncProgress(
            totalItems = missing.size,
            completedItems = 0,
            totalBytes = totalBytes,
            completedBytes = 0,
        )

        var completed = 0
        var completedBytes = 0L
        var failed = 0
        var lastError: String? = null

        for (media in missing) {
            if (!currentCoroutineContext().isActive) return
            val ext = app.ouie.screens.cache.CacheLayout.extensionFromR2Path(media.url)
            val result = downloader.download(media, expectedExt = ext)
            handleResult(media, ext, result)

            completed++
            when (result) {
                MediaDownloader.Result.Success -> {
                    completedBytes += media.size_bytes
                }
                MediaDownloader.Result.InsufficientSpace -> {
                    failed++
                    lastError = "Cache full; eviction could not make room"
                }
                is MediaDownloader.Result.ChecksumMismatch -> {
                    failed++
                    lastError = "Checksum mismatch"
                }
                is MediaDownloader.Result.NetworkError -> {
                    failed++
                    lastError = "Network error: ${result.cause?.message ?: "code ${result.code ?: "?"}"}"
                }
            }

            _progress.value = SyncProgress(
                totalItems = missing.size,
                completedItems = completed,
                totalBytes = totalBytes,
                completedBytes = completedBytes,
                lastError = lastError,
                failedItems = failed,
            )
        }
    }

    private fun handleResult(media: MediaDto, ext: String, r: MediaDownloader.Result) {
        when (r) {
            MediaDownloader.Result.Success -> {
                cache.markCached(
                    MediaCacheIndex.Entry(
                        mediaId = media.id,
                        ext = ext,
                        checksum = media.checksum,
                        sizeBytes = media.size_bytes,
                        cachedAtEpochSeconds = java.time.Instant.now().epochSecond,
                        lastPlayedAtEpochSeconds = null,
                    ),
                )
                reporter.cached(media.id)
            }
            MediaDownloader.Result.InsufficientSpace -> {
                reporter.failed(media.id, "cache full; eviction could not make room")
            }
            is MediaDownloader.Result.ChecksumMismatch -> {
                reporter.failed(
                    media.id,
                    "checksum mismatch: expected=${r.expected.take(12)}… got=${r.actual.take(12)}…",
                )
            }
            is MediaDownloader.Result.NetworkError -> {
                reporter.failed(
                    media.id,
                    "network: code=${r.code ?: "?"} cause=${r.cause?.javaClass?.simpleName ?: "-"}",
                )
            }
        }
    }
}
