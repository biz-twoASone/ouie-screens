package app.ouie.screens.pairing

import app.ouie.screens.auth.DeviceTokens
import app.ouie.screens.auth.TokenSource
import app.ouie.screens.net.PairingApi
import app.ouie.screens.net.PairingRequestBody
import app.ouie.screens.net.PairingRequestResponse
import app.ouie.screens.net.PairingStatusResponse
import app.ouie.screens.net.RecoveryAdapter
import app.ouie.screens.state.AppState
import app.ouie.screens.state.AppStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain

import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for the ESSEL Bogor Pajajaran "cannot re-pair" outage.
 *
 * The TVs issued three pairing codes on 2026-07-18 and then ZERO for 30 days
 * while every backend endpoint stayed verifiably healthy. Cause: the ViewModel
 * is retained in the Activity's ViewModelStore, so `init` runs once per
 * process; `loop()` returns on a requestCode failure; and the recovery path
 * (ErrorScreen countdown → recoverToPairing → Pairing → PairingScreen) resolves
 * that same retained instance, re-arming nothing. The retry was a no-op and the
 * TV never contacted the server again.
 *
 * These tests pin `ensureStarted()` — the method PairingScreen calls on every
 * (re)entry — as the thing that makes retry actually retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelRearmTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Fails every requestCode, counting attempts. */
    private class AlwaysFailingApi : PairingApi {
        val attempts = AtomicInteger(0)
        override suspend fun requestCode(body: PairingRequestBody): PairingRequestResponse {
            attempts.incrementAndGet()
            throw IOException("connection refused")
        }
        override suspend fun status(code: String): Response<PairingStatusResponse> =
            throw UnsupportedOperationException("not reached — requestCode fails first")
    }

    private class NoTokens : TokenSource {
        override fun loadSync(): DeviceTokens? = null
        override fun save(tokens: DeviceTokens) = Unit
        override fun clear() = Unit
        override fun clearAll() = Unit
        override fun loadIdentitySync(): String? = null
        override fun saveIdentity(identityToken: String) = Unit
    }

    private class NeverRecovers : RecoveryAdapter {
        override suspend fun recover(identityToken: String, screenId: String?): DeviceTokens =
            throw UnsupportedOperationException("no identity token in these tests")
    }

    private fun viewModelWith(api: AlwaysFailingApi, appState: AppStateHolder) =
        PairingViewModel(
            repo = PairingRepository(api = api, proposedName = "test-tv", pollIntervalMs = 1),
            tokenStore = NoTokens(),
            recoveryAdapter = NeverRecovers(),
            appState = appState,
        )

    // Deliberately NOT `runTest`: viewModelScope dispatches on Dispatchers.Main
    // (an UnconfinedTestDispatcher here), not on runTest's own scheduler, so
    // runTest's leaked-coroutine and uncaught-exception checks fire on
    // coroutines it doesn't own. Unconfined already runs everything eagerly on
    // the calling thread, so the assertions below are deterministic without it.
    @Test
    fun `a failed requestCode surfaces an error carrying the real cause`() {
        val api = AlwaysFailingApi()
        val appState = AppStateHolder()
        viewModelWith(api, appState)

        val state = appState.state.value
        assertTrue("expected Error, got $state", state is AppState.Error)
        state as AppState.Error
        assertEquals(AppState.ErrorKind.NetworkUnavailable, state.kind)
        assertEquals("pairing-request · connection refused", state.detail)
        assertEquals(1, api.attempts.get())
    }

    // THE regression. Before ensureStarted() this stayed at 1 forever, which is
    // exactly what the emulator repro showed: one "requestCode failed" in
    // logcat and never a second, no matter how long you waited.
    @Test
    fun `re-entering the pairing screen re-arms the flow and retries`() {
        val api = AlwaysFailingApi()
        val vm = viewModelWith(api, AppStateHolder())
        assertEquals("init should make the first attempt", 1, api.attempts.get())

        // Simulates ErrorScreen's countdown → recoverToPairing() → Pairing →
        // PairingScreen's LaunchedEffect firing again on the retained instance.
        vm.ensureStarted()
        assertEquals("retry must actually hit the server again", 2, api.attempts.get())

        vm.ensureStarted()
        assertEquals(3, api.attempts.get())
    }

    @Test
    fun `ensureStarted is idempotent while a run is still in flight`() {
        // A never-completing request keeps the job active; re-entry must not
        // stack a second concurrent pairing loop (which would burn pairing
        // codes and race two pollers against one code). The gate is
        // intentionally never completed — the suspended coroutine is the point.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hangingApi = object : PairingApi {
            val attempts = AtomicInteger(0)
            override suspend fun requestCode(body: PairingRequestBody): PairingRequestResponse {
                attempts.incrementAndGet()
                gate.await()
                error("unreachable — gate is never completed")
            }
            override suspend fun status(code: String): Response<PairingStatusResponse> =
                throw UnsupportedOperationException("not reached")
        }
        val vm = PairingViewModel(
            repo = PairingRepository(api = hangingApi, proposedName = "t", pollIntervalMs = 1),
            tokenStore = NoTokens(),
            recoveryAdapter = NeverRecovers(),
            appState = AppStateHolder(),
        )
        assertEquals(1, hangingApi.attempts.get())

        vm.ensureStarted()
        vm.ensureStarted()
        assertEquals("must not start a second concurrent loop", 1, hangingApi.attempts.get())
    }
}
