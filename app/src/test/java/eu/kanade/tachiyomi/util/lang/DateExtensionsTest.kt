package eu.kanade.tachiyomi.util.lang

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone

class DateExtensionsTest {

    @Test
    fun `date key changes at local midnight rather than UTC midnight`() = withTimeZone("America/New_York") {
        val zone = ZoneId.systemDefault()
        val beforeMidnight = ZonedDateTime.of(2026, 8, 19, 23, 30, 0, 0, zone)
        val afterMidnight = beforeMidnight.plusHours(1)

        beforeMidnight.toDateKey() shouldBe LocalDate.of(2026, 8, 19).startOfDay(zone)
        afterMidnight.toDateKey() shouldBe LocalDate.of(2026, 8, 20).startOfDay(zone)
    }

    @Test
    fun `instants spanning UTC midnight remain in one local date group`() = withTimeZone("America/New_York") {
        val zone = ZoneId.systemDefault()
        val beforeUtcMidnight = ZonedDateTime.of(2026, 8, 19, 19, 30, 0, 0, zone)
        val afterUtcMidnight = beforeUtcMidnight.plusHours(4)

        beforeUtcMidnight.toDateKey() shouldBe afterUtcMidnight.toDateKey()
    }

    @Test
    fun `date key uses the correct offset on a daylight saving transition`() =
        withTimeZone("America/New_York") {
            val zone = ZoneId.systemDefault()
            val beforeSpringForward = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, zone)
            val afterSpringForward = beforeSpringForward.plusHours(2)
            val expected = LocalDate.of(2026, 3, 8).startOfDay(zone)

            beforeSpringForward.toDateKey() shouldBe expected
            afterSpringForward.toDateKey() shouldBe expected
        }

    private fun ZonedDateTime.toDateKey(): Date = toInstant().toEpochMilli().toDateKey()

    private fun LocalDate.startOfDay(zone: ZoneId): Date = Date.from(atStartOfDay(zone).toInstant())

    private fun withTimeZone(timeZoneId: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timeZoneId))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
