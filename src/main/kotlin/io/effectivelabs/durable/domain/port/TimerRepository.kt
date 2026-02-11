package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.TimerRecord
import java.time.Instant

interface TimerRepository {
    fun create(record: TimerRecord)
    fun findExpired(now: Instant, limit: Int = 100): List<TimerRecord>
    fun markFired(record: TimerRecord)
}
