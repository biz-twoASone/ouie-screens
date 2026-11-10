// android-tv/app/src/main/java/app/ouie/screens/di/AppModule.kt
package app.ouie.screens.di

import app.ouie.screens.auth.TokenSource
import app.ouie.screens.auth.TokenStore
import app.ouie.screens.coordinator.RunningCoordinator
import app.ouie.screens.errorbus.ErrorBus
import app.ouie.screens.update.ApkInstaller
import app.ouie.screens.update.PackageInstallerHelper
import app.ouie.screens.fcm.FcmReceiptTracker
import app.ouie.screens.fcm.FcmTokenSource
import app.ouie.screens.fcm.SyncNowBroadcast
import app.ouie.screens.heartbeat.ClockSkewTracker
import app.ouie.screens.net.ApiBaseAdopter
import app.ouie.screens.net.ApiBaseProbe
import app.ouie.screens.net.ApiBaseStore
import app.ouie.screens.net.ApiClient
import app.ouie.screens.net.HttpApiBaseProbe
import app.ouie.screens.net.AuthInterceptor
import app.ouie.screens.net.CacheStatusApi
import app.ouie.screens.net.ConfigApi
import app.ouie.screens.net.DateHeaderInterceptor
import app.ouie.screens.net.DeviceApi
import app.ouie.screens.net.HeartbeatApi
import app.ouie.screens.net.PairingApi
import app.ouie.screens.net.RecoveryAdapter
import app.ouie.screens.net.RefreshAdapter
import app.ouie.screens.net.RetrofitRecoveryAdapter
import app.ouie.screens.net.RetrofitRefreshAdapter
import app.ouie.screens.net.TokenAuthenticator
import app.ouie.screens.pairing.PairingRepository
import app.ouie.screens.pairing.PairingViewModel
import app.ouie.screens.state.AppStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

val appModule = module {
    single { AppStateHolder() }
    single<TokenSource> { TokenStore(androidContext()) }
    single { ClockSkewTracker() }
    single { Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false } }

    // App-wide error bus. Consumers report; HeartbeatScheduler drains.
    single { ErrorBus(capacity = 32) }

    // SyncNowBroadcast connects the FCM service and the coordinator.
    single { SyncNowBroadcast() }

    // FCM token cache — lives as long as the app process.
    single { FcmTokenSource(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    single { FcmReceiptTracker() }

    // Effective screens API base: compiled, or a validated server hint (ouie S5c).
    // Every API client below routes through it; media/OTA clients do not.
    single { ApiBaseStore(File(androidContext().filesDir, "signage/api_base_url"), ApiClient.compiledBaseUrl()) }
    // Probe client: no base rewrite, no authenticator, no redirects (see HttpApiBaseProbe).
    single(qualifier = named("api_base_probe")) {
        ApiClient.baseHttpClient()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    single<ApiBaseProbe> { HttpApiBaseProbe(get(qualifier = named("api_base_probe")), get()) }
    single { ApiBaseAdopter(store = get(), probe = get(), tokens = get(), errorBus = get()) }

    // Pairing client — no auth, no skew tracking.
    single(qualifier = named("pairing")) { ApiClient.baseHttpClient(get<ApiBaseStore>()).build() }
    single { ApiClient.retrofit(get(qualifier = named("pairing"))).create(PairingApi::class.java) }

    // Refresh client — no authenticator, to break the chicken-and-egg inside refresh.
    // Same DeviceApi exposes both refresh and recover (identity-token
    // recovery, PR-#826 mirror). Both adapters share the no-auth client.
    single(qualifier = named("device_refresh")) { ApiClient.baseHttpClient(get<ApiBaseStore>()).build() }
    single { ApiClient.retrofit(get(qualifier = named("device_refresh"))).create(DeviceApi::class.java) }
    single<RefreshAdapter> { RetrofitRefreshAdapter(get()) }
    single<RecoveryAdapter> { RetrofitRecoveryAdapter(get()) }

    single(qualifier = named("authed")) {
        ApiClient.baseHttpClient(get<ApiBaseStore>())
            .addInterceptor(AuthInterceptor(get()))
            .addInterceptor(DateHeaderInterceptor(get()))
            .authenticator(TokenAuthenticator(get(), get(), get()))
            .build()
    }
    single { ApiClient.retrofit(get<OkHttpClient>(qualifier = named("authed"))).create(ConfigApi::class.java) }
    single { ApiClient.retrofit(get<OkHttpClient>(qualifier = named("authed"))).create(HeartbeatApi::class.java) }
    single { ApiClient.retrofit(get<OkHttpClient>(qualifier = named("authed"))).create(CacheStatusApi::class.java) }

    single(qualifier = named("downloader")) { ApiClient.baseHttpClient().build() }

    // Plan 5 Task 10 — OTA install path.
    single<ApkInstaller> { PackageInstallerHelper(androidContext(), get()) }

    single {
        RunningCoordinator(
            context = androidContext(),
            downloaderHttpClient = get(qualifier = named("downloader")),
            configApi = get(),
            heartbeatApi = get(),
            cacheStatusApi = get(),
            skewTracker = get(),
            json = get(),
            errorBus = get(),
            fcmTokenSource = get(),
            syncNow = get(),
            fcmReceiptTracker = get(),
            apkInstaller = get(),
            apiBaseAdopter = get(),
        )
    }

    single {
        PairingRepository(
            api = get(),
            proposedName = android.os.Build.MODEL ?: "Android TV",
        )
    }
    viewModel {
        PairingViewModel(
            repo = get(),
            tokenStore = get(),
            recoveryAdapter = get(),
            appState = get(),
            apiBase = get(),
        )
    }
}
