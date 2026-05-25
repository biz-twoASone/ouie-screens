// android-tv/app/src/main/java/app/ouie/screens/SignageApp.kt
package app.ouie.screens

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import app.ouie.screens.auth.TokenSource
import app.ouie.screens.di.appModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SignageApp : Application() {

    private val tokenStore: TokenSource by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SignageApp)
            modules(appModule)
        }
        val screenId = tokenStore.loadSync()?.screenId ?: "unpaired"
        FirebaseCrashlytics.getInstance().setUserId(screenId)
    }
}
