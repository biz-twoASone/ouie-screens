// android-tv/app/src/main/java/app/ouie/screens/net/ApiBaseStore.kt
package app.ouie.screens.net

import okhttp3.HttpUrl
import java.io.File
import java.io.IOException

/**
 * The effective screens API base: the adopted server hint when one has been
 * validated and persisted, otherwise the [compiled] base baked into the APK.
 *
 * Kept in its own file, apart from the token store: switching or forgetting a
 * base never touches pairing credentials, and clearing credentials never
 * forgets the base (a re-pair must happen on the active host). A missing,
 * unreadable or malformed file means the compiled base.
 */
class ApiBaseStore(
    private val file: File,
    val compiled: HttpUrl,
) {
    private val lock = Any()

    @Volatile
    private var adopted: HttpUrl? = load()

    /** Read on every API request, so it is served from memory. */
    fun current(): HttpUrl = adopted ?: compiled

    /**
     * Persists [base] before making it visible, so a crash never leaves the
     * process on a base it would forget at the next start. Passing null or the
     * compiled base forgets the hint.
     *
     * @throws IOException when the change could not be made durable; the
     *   effective base is then unchanged.
     */
    fun set(base: HttpUrl?) {
        synchronized(lock) {
            if (base == null || base == compiled) {
                if (file.exists() && !file.delete()) throw IOException("could not forget api base")
                adopted = null
                return
            }
            val dir = file.absoluteFile.parentFile ?: throw IOException("api base file has no directory")
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(base.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not persist api base")
            }
            adopted = base
        }
    }

    private fun load(): HttpUrl? = try {
        if (file.exists()) ApiBaseUrl.parseHint(file.readText())?.takeIf { it != compiled } else null
    } catch (_: IOException) {
        null
    }
}
