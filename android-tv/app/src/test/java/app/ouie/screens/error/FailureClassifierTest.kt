package app.ouie.screens.error

import app.ouie.screens.net.RecoveryFailedException
import app.ouie.screens.net.RefreshFailedException
import app.ouie.screens.state.AppState
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class FailureClassifierTest {

    private fun httpError(code: Int): HttpException =
        HttpException(
            Response.error<Unit>(
                code,
                "".toResponseBody("text/plain".toMediaType()),
            ),
        )

    @Test
    fun `dns failure is a network problem`() {
        val d = FailureClassifier.classify("pairing-request", UnknownHostException("glvo.supabase.co"))
        assertEquals(AppState.ErrorKind.NetworkUnavailable, d.kind)
        assertEquals("pairing-request · DNS lookup failed", d.detail)
    }

    @Test
    fun `timeout refused and no-route are network problems`() {
        assertEquals(
            AppState.ErrorKind.NetworkUnavailable,
            FailureClassifier.classify("op", SocketTimeoutException()).kind,
        )
        assertEquals(
            "op · connection timed out",
            FailureClassifier.classify("op", SocketTimeoutException()).detail,
        )
        assertEquals(
            "op · connection refused",
            FailureClassifier.classify("op", ConnectException()).detail,
        )
        assertEquals(
            "op · no route to host",
            FailureClassifier.classify("op", NoRouteToHostException()).detail,
        )
    }

    // The whole point of the change: a TLS failure must NOT read as
    // "can't reach our server" (it reached it) nor as "no network".
    @Test
    fun `TLS failure gets its own kind and points at the clock`() {
        val d = FailureClassifier.classify("pairing-request", SSLHandshakeException("cert not valid yet"))
        assertEquals(AppState.ErrorKind.SecureConnectionFailed, d.kind)
        assertTrue(d.detail, d.detail.contains("date & time"))
    }

    @Test
    fun `TLS subclasses are caught before the generic IOException branch`() {
        // SSLPeerUnverifiedException is an SSLException which is an IOException;
        // branch ordering decides which one wins.
        assertEquals(
            AppState.ErrorKind.SecureConnectionFailed,
            FailureClassifier.classify("op", SSLPeerUnverifiedException("nope")).kind,
        )
    }

    @Test
    fun `an HTTP response surfaces the status code`() {
        val d = FailureClassifier.classify("pairing-request", httpError(500))
        assertEquals(AppState.ErrorKind.ServerUnavailable, d.kind)
        assertEquals("pairing-request · HTTP 500", d.detail)
    }

    // Regression guard for the deliberate non-goal documented on
    // FailureClassifier: 401 must NOT become TokensInvalid, or a gateway
    // misconfiguration (verify_jwt drift) gets laundered into a re-pair loop.
    @Test
    fun `401 on an unauthenticated endpoint stays ServerUnavailable`() {
        val d = FailureClassifier.classify("pairing-request", httpError(401))
        assertEquals(AppState.ErrorKind.ServerUnavailable, d.kind)
        assertEquals("pairing-request · HTTP 401", d.detail)
    }

    @Test
    fun `refresh and recover failures carry their http code`() {
        assertEquals(
            "recover · recover HTTP 404",
            FailureClassifier.classify("recover", RecoveryFailedException(404)).detail,
        )
        assertEquals(
            "refresh · refresh HTTP 401",
            FailureClassifier.classify("refresh", RefreshFailedException(401)).detail,
        )
    }

    @Test
    fun `malformed body is a server problem not a network one`() {
        val d = FailureClassifier.classify("pairing-request", SerializationException("bad json"))
        assertEquals(AppState.ErrorKind.ServerUnavailable, d.kind)
        assertEquals("pairing-request · malformed response", d.detail)
    }

    @Test
    fun `other IOExceptions keep their message`() {
        val d = FailureClassifier.classify("op", IOException("unexpected end of stream"))
        assertEquals(AppState.ErrorKind.NetworkUnavailable, d.kind)
        assertEquals("op · unexpected end of stream", d.detail)
    }

    @Test
    fun `a messageless IOException falls back to its class name`() {
        val d = FailureClassifier.classify("op", IOException())
        assertEquals("op · IOException", d.detail)
    }

    @Test
    fun `non-IO throwables are Unknown and name their class`() {
        val d = FailureClassifier.classify("op", IllegalStateException("boom"))
        assertEquals(AppState.ErrorKind.Unknown, d.kind)
        assertEquals("op · IllegalStateException", d.detail)
    }

    @Test
    fun `long IO messages are truncated so they fit one TV line`() {
        val d = FailureClassifier.classify("op", IOException("x".repeat(200)))
        assertEquals("op · " + "x".repeat(60), d.detail)
    }
}
