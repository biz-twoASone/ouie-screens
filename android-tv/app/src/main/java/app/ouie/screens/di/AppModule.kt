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
import app.ouie.screens.net.ApiClient
import app.ouie.screens.net.AuthInterceptor
import app.ouie.screens.net.CacheStatusApi
import app.ouie.screens.net.ConfigApi
import app.ouie.screens.net.DateHeaderInterceptor
import app.ouie.screens.net.DeviceApi
import app.ouie.screens.net.HeartbeatApi
import app.ouie.screens.net.PairingApi
import app.ouie.screens.net.RefreshAdapter
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

    // Pairing client — no auth, no skew tracking.
    single(qualifier = named("pairing")) { ApiClient.baseHttpClient().build() }
    single { ApiClient.retrofit(get(qualifier = named("pairing"))).create(PairingApi::class.java) }

    // Refresh client — no authenticator, to break the chicken-and-egg inside refresh.
    single(qualifier = named("device_refresh")) { ApiClient.baseHttpClient().build() }
    single { ApiClient.retrofit(get(qualifier = named("device_refresh"))).create(DeviceApi::class.java) }
    single<RefreshAdapter> { RetrofitRefreshAdapter(get()) }

    single(qualifier = named("authed")) {
        ApiClient.baseHttpClient()
            .addInterceptor(AuthInterceptor(get()))
            .addInterceptor(DateHeaderInterceptor(get()))
            .authenticator(TokenAuthenticator(get(), get()))
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
        )
    }

    single {
        PairingRepository(
            api = get(),
            proposedName = android.os.Build.MODEL ?: "Android TV",
        )
    }
    viewModel { PairingViewModel(repo = get(), tokenStore = get(), appState = get()) }
}
