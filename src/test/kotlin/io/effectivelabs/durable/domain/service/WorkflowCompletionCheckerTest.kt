package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.Instant
import java.util.UUID

class WorkflowCompletionCheckerTest {

    private val checker = WorkflowCompletionChecker()
    private val workflowRunId = UUID.randomUUID()

    @Test
    fun `all COMPLETED returns COMPLETED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.COMPLETED),
            taskRecord("c", TaskState.COMPLETED),
        )

        assertEquals(RunStatus.COMPLETED, checker.check(tasks))
    }

    @Test
    fun `any FAILED returns FAILED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.FAILED),
            taskRecord("c", TaskState.COMPLETED),
        )

        assertEquals(RunStatus.FAILED, checker.check(tasks))
    }

    @Test
    fun `mix of COMPLETED and SKIPPED returns COMPLETED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.SKIPPED),
            taskRecord("c", TaskState.COMPLETED),
        )

        assertEquals(RunStatus.COMPLETED, checker.check(tasks))
    }

    @Test
    fun `RUNNING task present returns null`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.RUNNING),
            taskRecord("c", TaskState.COMPLETED),
        )

        assertNull(checker.check(tasks))
    }

    @Test
    fun `PENDING task present returns null`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.PENDING),
        )

        assertNull(checker.check(tasks))
    }

    @Test
    fun `SLEEPING task present returns null`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.SLEEPING),
        )

        assertNull(checker.check(tasks))
    }

    @Test
    fun `QUEUED task present returns null`() {
        val tasks = listOf(
            taskRecord("a", TaskState.COMPLETED),
            taskRecord("b", TaskState.QUEUED),
        )

        assertNull(checker.check(tasks))
    }

    @Test
    fun `mix of FAILED and CANCELLED returns FAILED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.FAILED),
            taskRecord("b", TaskState.CANCELLED),
        )

        assertEquals(RunStatus.FAILED, checker.check(tasks))
    }

    @Test
    fun `FAILED with orphaned PENDING and no active tasks returns FAILED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.FAILED),
            taskRecord("b", TaskState.PENDING),
        )

        assertEquals(RunStatus.FAILED, checker.check(tasks))
    }

    @Test
    fun `FAILED with QUEUED task still active returns null`() {
        val tasks = listOf(
            taskRecord("a", TaskState.FAILED),
            taskRecord("b", TaskState.QUEUED),
        )

        assertNull(checker.check(tasks))
    }

    @Test
    fun `all CANCELLED returns COMPLETED`() {
        val tasks = listOf(
            taskRecord("a", TaskState.CANCELLED),
            taskRecord("b", TaskState.CANCELLED),
        )

        assertEquals(RunStatus.COMPLETED, checker.check(tasks))
    }

    private fun taskRecord(name: String, status: TaskState): TaskRecord =
        TaskRecord(
            workflowRunId = workflowRunId,
            taskName = name,
            status = status,
            parentNames = emptyList(),
            pendingParentCount = 0,
            output = null,
            error = null,
            retryCount = 0,
            maxRetries = 0,
            createdAt = Instant.EPOCH,
            startedAt = null,
            completedAt = null,
        )
}
