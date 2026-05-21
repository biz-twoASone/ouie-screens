// android-tv/app/src/main/java/app/ouie/screens/schedule/TimeZoneClock.kt
package app.ouie.screens.schedule

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Thin wrapper around Clock so tests can pin time to known instants. Production
 * code injects `TimeZoneClock()`; tests pass a fixed clock via
 * `TimeZoneClock(Clock.fixed(instant, zone))`.
 */
class TimeZoneClock(private val clock: Clock = Clock.systemUTC()) {
    fun nowInstant(): Instant = clock.instant()
    fun nowIn(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(nowInstant(), zone)
}
