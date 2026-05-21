// android-tv/app/src/main/java/app/ouie/screens/heartbeat/HeartbeatScheduler.kt
package app.ouie.screens.heartbeat

import android.os.SystemClock
import app.ouie.screens.BuildConfig
import app.ouie.screens.cache.CacheRootResolver
import app.ouie.screens.config.ConfigRepository
import app.ouie.screens.errorbus.ErrorBus
import app.ouie.screens.fcm.FcmReceiptTracker
import app.ouie.screens.fcm.FcmTokenSource
import app.ouie.screens.net.HeartbeatApi
import app.ouie.screens.playback.PlaybackStateSource
import app.ouie.screens.preload.PreloadStatusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface CurrentPlaylistSource {
    fun current(): String?
}

class HeartbeatScheduler(
    private val scope: CoroutineScope,
    private val api: HeartbeatApi,
    private val configRepo: ConfigRepository,
    private val skewTracker: ClockSkewTracker,
    private val playlistSource: CurrentPlaylistSource,
    private val pickProvider: () -> CacheRootResolver.Pick?,
    private val errorBus: ErrorBus,
    private val fcmTokenSource: FcmTokenSource,
    private val preloadStatusSource: PreloadStatusSource,
    private val fcmReceiptTracker: FcmReceiptTracker,
    private val playbackStateSource: PlaybackStateSource,
    private val intervalMs: Long = 60_000,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private var job: Job? = null
    // Single-writer: only touched from the sendOne() coroutine. No @Volatile needed.
    private var firstAfterBoot: Boolean = true
    private val processStartRealtime = nowMs()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                sendOne()
                try { delay(intervalMs) } catch (e: CancellationException) { throw e }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Plan 5 Task 21: speculative FCM-socket-stickiness mitigation. On the
     * first heartbeat after process start, force a token re-acquire — exercises
     * the GMS path which (per Plan 4.1 follow-up hypothesis) may unstick post-reboot
     * scenarios where the receive socket fails to re-establish on TCL Google
     * TV. Subsequent calls are no-ops. Failures swallowed (we don't want one
     * bad GMS call to abort the heartbeat itself).
     *
     * Internal visibility so unit tests can drive this in isolation.
     */
    internal suspend fun maybeForceFcmRefresh() {
        if (!firstAfterBoot) return
        firstAfterBoot = false
        try {
            fcmTokenSource.forceRefresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Swallow — heartbeat will carry whatever cached() returns.
        }
    }

    private suspend fun sendOne() {
        maybeForceFcmRefresh()
        val uptimeSeconds = (nowMs() - processStartRealtime) / 1000
        val pick = pickProvider()
        val cacheInfo = pick?.let {
            CacheStorageInfoBuilder.buildFrom(it, preloadStatusSource.current())
        }
        val errors = errorBus.drain()
        val fcm = fcmTokenSource.current()
        val fcmReceived = fcmReceiptTracker.current()?.toString()
        val playbackSnapshot = playbackStateSource.snapshot()
        val payload = HeartbeatPayload(
            app_version = BuildConfig.VERSION_NAME,
            uptime_seconds = uptimeSeconds,
            current_playlist_id = playlistSource.current(),
            last_config_version_applied = configRepo.current.value?.version,
            clock_skew_seconds_from_server = skewTracker.current(),
            cache_storage_info = cacheInfo,
            errors_since_last_heartbeat = errors,
            fcm_token = fcm,
            last_fcm_received_at = fcmReceived,
            current_media_id = playbackSnapshot.currentMediaId,
            playback_state = playbackSnapshot.stateTag,
        )
        try {
            api.post(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best-effort; next tick retries. We DO NOT re-enqueue drained errors —
            // single-send-best-effort matches the "errors_since_last_heartbeat" spec
            // semantics and avoids unbounded error carryover.
        }
    }
}
