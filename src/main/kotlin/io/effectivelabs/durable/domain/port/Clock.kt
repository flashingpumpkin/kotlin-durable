package io.effectivelabs.durable.domain.port

import java.time.Instant

interface Clock {
    fun now(): Instant
}
