package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.QueueItem

interface ReadyQueueRepository {
    fun enqueue(item: QueueItem)
    fun enqueueAll(items: List<QueueItem>)
    fun claim(batchSize: Int): List<QueueItem>
}
