package io.effectivelabs.durable.domain.port

interface IdGenerator {
    fun nextQueueId(): Long
}
