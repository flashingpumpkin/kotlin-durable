package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.QueueItem
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import java.util.PriorityQueue

class InMemoryReadyQueueRepository : ReadyQueueRepository {

    private val queue = PriorityQueue<QueueItem>(compareBy { it.id })

    @Synchronized
    override fun enqueue(item: QueueItem) {
        queue.add(item)
    }

    @Synchronized
    override fun enqueueAll(items: List<QueueItem>) {
        queue.addAll(items)
    }

    @Synchronized
    override fun claim(batchSize: Int): List<QueueItem> {
        val claimed = mutableListOf<QueueItem>()
        repeat(batchSize) {
            val item = queue.poll() ?: return claimed
            claimed.add(item)
        }
        return claimed
    }
}
