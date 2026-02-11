package io.effectivelabs.durable.adapter.time

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Duration
import java.time.Instant

class FakeClockTest {

    @Test
    fun `initial time is the default epoch`() {
        val clock = FakeClock()

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), clock.now())
    }

    @Test
    fun `advance moves instant forward`() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))

        clock.advance(Duration.ofSeconds(5))

        assertEquals(Instant.parse("2026-01-01T00:00:05Z"), clock.now())
    }

    @Test
    fun `multiple advances accumulate`() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))

        clock.advance(Duration.ofSeconds(3))
        clock.advance(Duration.ofSeconds(7))

        assertEquals(Instant.parse("2026-01-01T00:00:10Z"), clock.now())
    }

    @Test
    fun `set jumps to exact time`() {
        val clock = FakeClock()
        val target = Instant.parse("2030-06-15T12:30:00Z")

        clock.set(target)

        assertEquals(target, clock.now())
    }

    @Test
    fun `custom initial time`() {
        val start = Instant.parse("2025-03-15T09:00:00Z")
        val clock = FakeClock(start)

        assertEquals(start, clock.now())
    }
}
