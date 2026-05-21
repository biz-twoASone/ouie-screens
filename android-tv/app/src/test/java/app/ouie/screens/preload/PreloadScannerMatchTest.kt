// android-tv/app/src/test/java/app/ouie/screens/preload/PreloadScannerMatchTest.kt
package app.ouie.screens.preload

import app.ouie.screens.config.ConfigDto
import app.ouie.screens.config.DeviceDto
import app.ouie.screens.config.MediaDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreloadScannerMatchTest {

    private fun cfg(vararg m: MediaDto): ConfigDto = ConfigDto(
        version = "v", device = DeviceDto("d", "s", null, "UTC"), media = m.toList(),
    )

    @Test
    fun `hash matches referenced media not yet cached — returns media id`() {
        val cfg = cfg(MediaDto("mA", "video", 100, checksum = "aa", url = ""))
        val id = PreloadScanner.matchHash(
            sha256 = "aa",
            config = cfg,
            cachedMediaIds = emptySet(),
        )
        assertEquals("mA", id)
    }

    @Test
    fun `hash matches already-cached media — returns null (no re-preload)`() {
        val cfg = cfg(MediaDto("mA", "video", 100, checksum = "aa", url = ""))
        val id = PreloadScanner.matchHash(
            sha256 = "aa",
            config = cfg,
            cachedMediaIds = setOf("mA"),
        )
        assertNull(id)
    }

    @Test
    fun `unknown hash returns null`() {
        val cfg = cfg(MediaDto("mA", "video", 100, checksum = "aa", url = ""))
        assertNull(
            PreloadScanner.matchHash(
                sha256 = "cc",
                config = cfg,
                cachedMediaIds = emptySet(),
            ),
        )
    }

    @Test
    fun `null config treats every hash as unmatched`() {
        assertNull(PreloadScanner.matchHash("aa", null, emptySet()))
    }
}
