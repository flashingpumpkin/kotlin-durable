package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.QueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class InMemoryReadyQueueRepositoryTest {

    @Test
    fun `enqueue and claim returns items in ID order`() {
        val repo = InMemoryReadyQueueRepository()
        val runId = UUID.randomUUID()

        repo.enqueue(queueItem(id = 3, runId, "c"))
        repo.enqueue(queueItem(id = 1, runId, "a"))
        repo.enqueue(queueItem(id = 2, runId, "b"))

        val claimed = repo.claim(batchSize = 3)

        assertEquals(3, claimed.size)
        assertEquals(listOf(1L, 2L, 3L), claimed.map { it.id })
    }

    @Test
    fun `claim removes items from queue`() {
        val repo = InMemoryReadyQueueRepository()
        val runId = UUID.randomUUID()

        repo.enqueue(queueItem(id = 1, runId, "a"))
        repo.enqueue(queueItem(id = 2, runId, "b"))

        val firstClaim = repo.claim(batchSize = 2)
        assertEquals(2, firstClaim.size)

        val secondClaim = repo.claim(batchSize = 2)
        assertTrue(secondClaim.isEmpty())
    }

    @Test
    fun `claim respects batchSize limit`() {
        val repo = InMemoryReadyQueueRepository()
        val runId = UUID.randomUUID()

        repo.enqueue(queueItem(id = 1, runId, "a"))
        repo.enqueue(queueItem(id = 2, runId, "b"))
        repo.enqueue(queueItem(id = 3, runId, "c"))

        val claimed = repo.claim(batchSize = 2)

        assertEquals(2, claimed.size)
        assertEquals(listOf(1L, 2L), claimed.map { it.id })
    }

    @Test
    fun `claim from empty queue returns empty list`() {
        val repo = InMemoryReadyQueueRepository()

        val claimed = repo.claim(batchSize = 10)

        assertTrue(claimed.isEmpty())
    }

    @Test
    fun `enqueueAll adds multiple items`() {
        val repo = InMemoryReadyQueueRepository()
        val runId = UUID.randomUUID()

        repo.enqueueAll(listOf(
            queueItem(id = 2, runId, "b"),
            queueItem(id = 1, runId, "a"),
        ))

        val claimed = repo.claim(batchSize = 2)
        assertEquals(listOf(1L, 2L), claimed.map { it.id })
    }

    private fun queueItem(id: Long, runId: UUID, taskName: String): QueueItem =
        QueueItem(
            id = id,
            workflowRunId = runId,
            taskName = taskName,
            enqueuedAt = Instant.EPOCH,
        )
}
