package io.effectivelabs.durable.adapter.time

import io.effectivelabs.durable.domain.port.Clock
import java.time.Duration
import java.time.Instant

class FakeClock(private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {

    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    fun set(newInstant: Instant) {
        instant = newInstant
    }
}
