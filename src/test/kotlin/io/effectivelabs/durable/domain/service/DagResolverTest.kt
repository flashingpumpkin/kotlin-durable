package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class DagResolverTest {

    private val resolver = DagResolver()
    private val workflowRunId = UUID.randomUUID()

    @Test
    fun `single parent completion makes child ready`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED, parentNames = emptyList(), pendingParentCount = 0),
            taskRecord("b", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "a")

        assertEquals(listOf("b"), resolution.readyTaskNames)
    }

    @Test
    fun `diamond fan-in first parent does not make child ready`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED, parentNames = emptyList(), pendingParentCount = 0),
            taskRecord("b", TaskState.COMPLETED, parentNames = listOf("a"), pendingParentCount = 0),
            taskRecord("c", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
            taskRecord("d", TaskState.PENDING, parentNames = listOf("b", "c"), pendingParentCount = 2),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "b")

        assertTrue(resolution.readyTaskNames.isEmpty())
    }

    @Test
    fun `diamond fan-in second parent makes child ready`() {
        val tasks = listOf(
            taskRecord("d", TaskState.PENDING, parentNames = listOf("b", "c"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "c")

        assertEquals(listOf("d"), resolution.readyTaskNames)
    }

    @Test
    fun `SKIPPED parent counts as resolved`() {
        val tasks = listOf(
            taskRecord("b", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "a")

        assertEquals(listOf("b"), resolution.readyTaskNames)
    }

    @Test
    fun `parent not in child parent list has no effect`() {
        val tasks = listOf(
            taskRecord("b", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "x")

        assertTrue(resolution.readyTaskNames.isEmpty())
    }

    @Test
    fun `non-PENDING child is not affected`() {
        val tasks = listOf(
            taskRecord("b", TaskState.RUNNING, parentNames = listOf("a"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "a")

        assertTrue(resolution.readyTaskNames.isEmpty())
    }

    @Test
    fun `multiple children become ready from same parent`() {
        val tasks = listOf(
            taskRecord("b", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
            taskRecord("c", TaskState.PENDING, parentNames = listOf("a"), pendingParentCount = 1),
        )

        val resolution = resolver.resolveChildren(tasks, completedParentName = "a")

        assertEquals(listOf("b", "c"), resolution.readyTaskNames.sorted())
    }

    private fun taskRecord(
        name: String,
        status: TaskState,
        parentNames: List<String>,
        pendingParentCount: Int,
    ): TaskRecord =
        TaskRecord(
            workflowRunId = workflowRunId,
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
