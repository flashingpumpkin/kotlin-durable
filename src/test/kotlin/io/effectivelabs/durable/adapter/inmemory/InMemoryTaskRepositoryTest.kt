package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class InMemoryTaskRepositoryTest {

    @Test
    fun `createAll stores and findByName retrieves records`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()
        val records = listOf(
            taskRecord(runId, "a"),
            taskRecord(runId, "b"),
        )

        repo.createAll(records)

        assertNotNull(repo.findByName(runId, "a"))
        assertNotNull(repo.findByName(runId, "b"))
        assertNull(repo.findByName(runId, "c"))
    }

    @Test
    fun `findAllByWorkflowRunId returns all tasks for a run`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()
        val otherRunId = UUID.randomUUID()

        repo.createAll(listOf(
            taskRecord(runId, "a"),
            taskRecord(runId, "b"),
            taskRecord(otherRunId, "c"),
        ))

        val tasks = repo.findAllByWorkflowRunId(runId)

        assertEquals(2, tasks.size)
        assertEquals(setOf("a", "b"), tasks.map { it.taskName }.toSet())
    }

    @Test
    fun `updateStatus changes task state`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(taskRecord(runId, "a")))
        repo.updateStatus(runId, "a", TaskState.RUNNING)

        val task = repo.findByName(runId, "a")
        assertEquals(TaskState.RUNNING, task?.status)
    }

    @Test
    fun `updateStatus stores output`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(taskRecord(runId, "a")))
        repo.updateStatus(runId, "a", TaskState.COMPLETED, output = "result-a")

        val task = repo.findByName(runId, "a")
        assertEquals("result-a", task?.output)
    }

    @Test
    fun `updateStatus stores error`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(taskRecord(runId, "a")))
        repo.updateStatus(runId, "a", TaskState.FAILED, error = "boom")

        val task = repo.findByName(runId, "a")
        assertEquals("boom", task?.error)
    }

    @Test
    fun `decrementPendingParents returns ready tasks when count reaches zero`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(
            taskRecord(runId, "a", parentNames = emptyList(), pendingParentCount = 0, status = TaskState.COMPLETED),
            taskRecord(runId, "b", parentNames = listOf("a"), pendingParentCount = 1),
        ))

        val ready = repo.decrementPendingParents(runId, "a")

        assertEquals(1, ready.size)
        assertEquals("b", ready[0].taskName)
        assertEquals(TaskState.QUEUED, ready[0].status)
        assertEquals(0, ready[0].pendingParentCount)
    }

    @Test
    fun `decrementPendingParents does not return tasks with remaining parents`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(
            taskRecord(runId, "d", parentNames = listOf("b", "c"), pendingParentCount = 2),
        ))

        val ready = repo.decrementPendingParents(runId, "b")

        assertTrue(ready.isEmpty())

        val task = repo.findByName(runId, "d")
        assertEquals(1, task?.pendingParentCount)
    }

    @Test
    fun `decrementPendingParents ignores non-PENDING tasks`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(
            taskRecord(runId, "b", parentNames = listOf("a"), pendingParentCount = 1, status = TaskState.RUNNING),
        ))

        val ready = repo.decrementPendingParents(runId, "a")

        assertTrue(ready.isEmpty())
    }

    @Test
    fun `decrementPendingParents ignores tasks where parent is not in parent list`() {
        val repo = InMemoryTaskRepository()
        val runId = UUID.randomUUID()

        repo.createAll(listOf(
            taskRecord(runId, "b", parentNames = listOf("a"), pendingParentCount = 1),
        ))

        val ready = repo.decrementPendingParents(runId, "x")

        assertTrue(ready.isEmpty())
    }

    private fun taskRecord(
        runId: UUID,
        name: String,
        parentNames: List<String> = emptyList(),
        pendingParentCount: Int = 0,
        status: TaskState = TaskState.PENDING,
    ): TaskRecord =
        TaskRecord(
            workflowRunId = runId,
            taskName = name,
            status = status,
            parentNames = parentNames,
            pendingParentCount = pendingParentCount,
            output = null,
            error = null,
            retryCount = 0,
            maxRetries = 0,
            createdAt = Instant.EPOCH,
            startedAt = null,
            completedAt = null,
        )
}
