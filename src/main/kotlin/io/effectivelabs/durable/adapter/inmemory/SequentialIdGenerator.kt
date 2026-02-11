package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.port.IdGenerator
import java.util.concurrent.atomic.AtomicLong

class SequentialIdGenerator : IdGenerator {

    private val counter = AtomicLong(0)

    override fun nextQueueId(): Long = counter.incrementAndGet()
}
