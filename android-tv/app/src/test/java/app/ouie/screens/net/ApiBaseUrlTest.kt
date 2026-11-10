// android-tv/app/src/test/java/app/ouie/screens/net/ApiBaseUrlTest.kt
package app.ouie.screens.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The client accepts exactly what the ouie S5c resolver emits
 * (`https://<canonical origin>/api/screens/v1/`) and ignores anything else.
 */
class ApiBaseUrlTest {

    @Test
    fun `accepts the canonical forms the server emits`() {
        for (hint in listOf(
            "https://screens.example.invalid/api/screens/v1/",
            "https://screens.example.invalid:8443/api/screens/v1/",
            "https://xn--bcher-kva.example/api/screens/v1/",
            "https://[::1]/api/screens/v1/",
            "https://127.0.0.1/api/screens/v1/",
        )) {
            assertEquals(hint, ApiBaseUrl.parseHint(hint)?.toString())
        }
    }

    @Test
    fun `rejects every non-HTTPS or non-canonical shape instead of repairing it`() {
        for (hint in listOf(
            "",
            "not a url",
            "http://screens.example.invalid/api/screens/v1/",
            "ftp://screens.example.invalid/api/screens/v1/",
            "//screens.example.invalid/api/screens/v1/",
            // path must be exactly the advertised API base
            "https://screens.example.invalid",
            "https://screens.example.invalid/",
            "https://screens.example.invalid/api/screens/v1",
            "https://screens.example.invalid/api/screens/v2/",
            "https://screens.example.invalid/functions/v1/",
            "https://screens.example.invalid/x/../api/screens/v1/",
            // credentials, including empty userinfo
            "https://user:pw@screens.example.invalid/api/screens/v1/",
            "https://user@screens.example.invalid/api/screens/v1/",
            "https://@screens.example.invalid/api/screens/v1/",
            // query / fragment, including bare delimiters
            "https://screens.example.invalid/api/screens/v1/?a=1",
            "https://screens.example.invalid/api/screens/v1/?",
            "https://screens.example.invalid/api/screens/v1/#f",
            "https://screens.example.invalid/api/screens/v1/#",
            // whitespace, controls, backslashes
            " https://screens.example.invalid/api/screens/v1/",
            "https://screens.example.invalid/api/screens/v1/\n",
            "https://screens.example.invalid/api/screens/v1/\t",
            "https://screens.example.invalid\\api/screens/v1/",
            "https://screens.example.invalid/api/screens/v1/\u007f",
            // non-canonical spellings the server never emits
            "HTTPS://screens.example.invalid/api/screens/v1/",
            "https://Screens.Example.Invalid/api/screens/v1/",
            "https://screens.example.invalid:443/api/screens/v1/",
            "https://bücher.example/api/screens/v1/",
        )) {
            assertNull("must reject <$hint>", ApiBaseUrl.parseHint(hint))
        }
    }
}
