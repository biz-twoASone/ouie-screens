// android-tv/app/src/main/java/app/ouie/screens/coordinator/RunningCoordinator.kt
package app.ouie.screens.coordinator

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import app.ouie.screens.cache.CacheLayout
import app.ouie.screens.cache.CacheManager
import app.ouie.screens.cache.CacheRootResolver
import app.ouie.screens.cache.MediaCacheIndex
import app.ouie.screens.config.ConfigPoller
import app.ouie.screens.config.ConfigRepository
import app.ouie.screens.config.ConfigStore
import app.ouie.screens.errorbus.ErrorBus
import app.ouie.screens.fcm.FcmReceiptTracker
import app.ouie.screens.fcm.FcmTokenSource
import app.ouie.screens.fcm.SyncNowBroadcast
import app.ouie.screens.heartbeat.ClockSkewTracker
import app.ouie.screens.heartbeat.HeartbeatScheduler
import app.ouie.screens.net.CacheStatusApi
import app.ouie.screens.net.ConfigApi
import app.ouie.screens.net.HeartbeatApi
import app.ouie.screens.playback.PlaybackDirector
import app.ouie.screens.preload.PreloadIndex
import app.ouie.screens.preload.PreloadScanner
import app.ouie.screens.sync.CacheStatusReporter
import app.ouie.screens.sync.MediaDownloader
import app.ouie.screens.sync.MediaSyncWorker
import app.ouie.screens.update.ApkInstaller
import app.ouie.screens.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

class RunningCoordinator(
    private val context: Context,
    private val downloaderHttpClient: OkHttpClient,
    private val configApi: ConfigApi,
    private val heartbeatApi: HeartbeatApi,
    private val cacheStatusApi: CacheStatusApi,
    private val skewTracker: ClockSkewTracker,
    private val json: Json,
    private val errorBus: ErrorBus,
    private val fcmTokenSource: FcmTokenSource,
    private val syncNow: SyncNowBroadcast,
    private val fcmReceiptTracker: FcmReceiptTracker,
    private val apkInstaller: ApkInstaller,
) {

    private var scope: CoroutineScope? = null
    private var configPoller: ConfigPoller? = null
    private var heartbeat: HeartbeatScheduler? = null
    private var sync: MediaSyncWorker? = null
    private var reporter: CacheStatusReporter? = null
    private var preloadScanner: PreloadScanner? = null
    private var configRepoRef: ConfigRepository? = null
    private var cacheRef: CacheManager? = null

    private val _cachePick = MutableStateFlow<CacheRootResolver.Pick?>(null)
    val cachePick: StateFlow<CacheRootResolver.Pick?> = _cachePick.asStateFlow()

    private val _playbackDirector = MutableStateFlow<PlaybackDirector?>(null)
    val playbackDirector: StateFlow<PlaybackDirector?> = _playbackDirector.asStateFlow()

    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        val pick = pickCacheRoot(context)
        _cachePick.value = pick
        val layout = CacheLayout(pick.root)
        layout.mediaDir().mkdirs()
        val index = MediaCacheIndex(context, layout.indexDbFile())
        val cache = CacheManager(layout, index)
        cacheRef = cache

        val configDir = File(context.filesDir, "signage/config")
        val configStore = ConfigStore(configDir, json)
        val configRepo = ConfigRepository(configApi, configStore)
        configRepoRef = configRepo

        val knownIds: List<String> = configRepo.current.value?.media?.map { it.id } ?: emptyList()
        cache.rehydrate(knownIds)

        val downloader = MediaDownloader(
            httpClient = downloaderHttpClient,
            layout = layout,
            ensureSpace = { bytes ->
                val referenced = configRepo.current.value?.playlists
                    ?.flatMap { pl -> pl.items.map { it.media_id } }
                    ?.toSet()
                    ?: emptySet()
                cache.ensureFreeSpaceFor(bytes, referencedMediaIds = referenced)
            },
        )
        val report = CacheStatusReporter(newScope, cacheStatusApi)
        reporter = report
        report.start()

        val syncer = MediaSyncWorker(
            scope = newScope,
            configRepo = configRepo,
            cache = cache,
            downloader = downloader,
            reporter = report,
            index = index,
        )
        sync = syncer
        syncer.start()

        val director = PlaybackDirector(
            config = configRepo.current,
            cachedMediaIds = cache.cached,
            fileFor = { id -> cache.fileFor(id) },
            syncProgress = syncer.progress,
        )
        _playbackDirector.value = director

        // Plan 5 Task 10: OTA — react to app_release on every config refresh.
        // updatesDir lives next to the media cache so OS-level "clear cache"
        // wipes both. UpdateChecker no-ops when version_code <= our own.
        val updatesDir = File(pick.root, "updates")
        val updater = UpdateChecker(
            httpClient = downloaderHttpClient,
            updatesDir = updatesDir,
            currentVersionCode = app.ouie.screens.BuildConfig.VERSION_CODE,
            installer = apkInstaller,
        )
        configRepo.current.onEach { cfg ->
            val release = cfg?.app_release ?: return@onEach
            try {
                updater.checkAndDownload(
                    UpdateChecker.Release(
                        version_code = release.version_code,
                        version_name = release.version_name,
                        sha256 = release.sha256,
                        url = release.url,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                errorBus.report("ota_check_failed", null, t.message)
            }
        }.launchIn(newScope)

        // Preload scanner — runs at start + on each config change.
        val preloadDir = File(pick.root.parentFile ?: pick.root, "preload")
        val preloadIndex = PreloadIndex(context, File(pick.root.parentFile ?: pick.root, "preload_index.db"))
        val scanner = PreloadScanner(
            preloadDir = preloadDir,
            cache = cache,
            index = preloadIndex,
            cacheIndex = index,
            reporter = report,
            errorBus = errorBus,
        )
        preloadScanner = scanner
        configRepo.current.onEach { cfg ->
            scanner.scanOnce(cfg)
        }.launchIn(newScope)

        val poller = ConfigPoller(newScope, configRepo)
        configPoller = poller
        poller.start()

        val beat = HeartbeatScheduler(
            scope = newScope,
            api = heartbeatApi,
            configRepo = configRepo,
            skewTracker = skewTracker,
            playlistSource = director,
            pickProvider = { _cachePick.value },
            errorBus = errorBus,
            fcmTokenSource = fcmTokenSource,
            preloadStatusSource = scanner,
            fcmReceiptTracker = fcmReceiptTracker,
            playbackStateSource = director,
        )
        heartbeat = beat
        beat.start()

        // FCM-driven sync-now: every broadcast triggers an immediate config refetch.
        syncNow.events.onEach {
            triggerSyncNow()
        }.launchIn(newScope)

        fcmTokenSource.prime()
        director.startTicker(newScope)
    }

    /**
     * Immediate-sync path (spec §6.4). Invoked by FCM data message or the
     * playback loop's "desired not cached" branch. Fires a config fetch + sync
     * cycle outside the 60 s poll cadence.
     */
    fun triggerSyncNow() {
        val s = scope ?: return
        val repo = configRepoRef ?: return
        s.launch {
            try {
                repo.fetch()  // MediaSyncWorker collects configRepo.current, auto-reacts
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                errorBus.report("sync_now_failed", null, t.message)
            }
        }
    }

    fun stop() {
        _playbackDirector.value?.stopTicker()
        _playbackDirector.value = null
        configPoller?.stop(); configPoller = null
        heartbeat?.stop();    heartbeat = null
        sync?.stop();         sync = null
        reporter?.stop();     reporter = null
        preloadScanner = null
        configRepoRef = null
        cacheRef = null
        scope?.cancel()
        scope = null
        _cachePick.value = null
    }

    private fun pickCacheRoot(context: Context): CacheRootResolver.Pick {
        val externalDirs = context.getExternalFilesDirs(null).filterNotNull().filter { it.exists() }
        val primary = externalDirs.drop(1)
        val candidates = primary.map { dir ->
            val stats = try { StatFs(dir.absolutePath) } catch (_: Throwable) { null }
            val free = stats?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
            CacheRootResolver.Candidate(dir = File(dir, "cache"), freeBytes = free, isExternal = true)
        }
        val internalDir = File(context.filesDir, "signage/cache")
        internalDir.mkdirs()
        val internalStats = try { StatFs(internalDir.absolutePath) } catch (_: Throwable) { null }
        val internalFree = internalStats?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val additional: List<CacheRootResolver.Candidate> = try {
            sm?.storageVolumes?.mapNotNull { v ->
                if (v.isPrimary) return@mapNotNull null
                val dir = v.directory ?: return@mapNotNull null
                val stats = try { StatFs(dir.absolutePath) } catch (_: Throwable) { null }
                val free = stats?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
                CacheRootResolver.Candidate(dir = File(dir, "signage/cache"), freeBytes = free, isExternal = true)
            } ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        return CacheRootResolver.pick(
            candidates = (candidates + additional).distinctBy { it.dir.absolutePath },
            internalDir = internalDir,
            internalFreeBytes = internalFree,
            minExternalBytes = 4L * 1024 * 1024 * 1024,
        )
    }
}
