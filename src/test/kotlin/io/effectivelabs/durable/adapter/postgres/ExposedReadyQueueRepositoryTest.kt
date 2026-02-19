package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.model.QueueItem
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposedReadyQueueRepositoryTest : PostgresTestBase() {

    private val repo = ExposedReadyQueueRepository()

    @BeforeTest
    fun setUp() {
        cleanTables()
    }

    private fun queueItem(id: Long = 0, taskName: String = "task-1") = QueueItem(
        id = id,
        workflowRunId = UUID.randomUUID(),
        taskName = taskName,
        enqueuedAt = Instant.now(),
    )

    @Test
    fun `enqueue and claim returns item`() {
        repo.enqueue(queueItem(taskName = "a"))

        val claimed = repo.claim(10)
        assertEquals(1, claimed.size)
        assertEquals("a", claimed[0].taskName)
    }

    @Test
    fun `claim removes items from queue`() {
        repo.enqueue(queueItem(taskName = "a"))
        repo.enqueue(queueItem(taskName = "b"))

        val first = repo.claim(1)
        assertEquals(1, first.size)

        val second = repo.claim(1)
        assertEquals(1, second.size)

        val third = repo.claim(1)
        assertEquals(0, third.size)
    }

    @Test
    fun `enqueueAll inserts multiple items`() {
        repo.enqueueAll(
            listOf(
                queueItem(taskName = "a"),
                queueItem(taskName = "b"),
                queueItem(taskName = "c"),
            )
        )

        val claimed = repo.claim(10)
        assertEquals(3, claimed.size)
    }

    @Test
    fun `claim respects batchSize limit`() {
        repo.enqueueAll(
            listOf(
                queueItem(taskName = "a"),
                queueItem(taskName = "b"),
                queueItem(taskName = "c"),
            )
        )

        val claimed = repo.claim(2)
        assertEquals(2, claimed.size)
    }

    @Test
    fun `concurrent claims with SKIP LOCKED never double-claim`() {
        // Enqueue 20 items
        val items = (1..20).map { queueItem(taskName = "task-$it") }
        repo.enqueueAll(items)

        val allClaimed = ConcurrentHashMap.newKeySet<Long>()
        val duplicates = ConcurrentHashMap.newKeySet<Long>()
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)

        val futures = (1..4).map {
            executor.submit {
                latch.await()
                val claimed = repo.claim(10)
                for (item in claimed) {
                    if (!allClaimed.add(item.id)) {
                        duplicates.add(item.id)
                    }
                }
            }
        }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertTrue(duplicates.isEmpty(), "Found duplicate claims: $duplicates")
        assertEquals(20, allClaimed.size, "All 20 items should be claimed exactly once")
    }
}
