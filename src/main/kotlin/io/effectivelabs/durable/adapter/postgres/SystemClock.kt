package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.port.Clock
import java.time.Instant

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
