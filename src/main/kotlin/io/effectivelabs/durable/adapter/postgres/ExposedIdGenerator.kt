package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.port.IdGenerator
import java.util.concurrent.atomic.AtomicLong

class ExposedIdGenerator : IdGenerator {

    private val counter = AtomicLong(0)

    override fun nextQueueId(): Long = counter.incrementAndGet()
}
