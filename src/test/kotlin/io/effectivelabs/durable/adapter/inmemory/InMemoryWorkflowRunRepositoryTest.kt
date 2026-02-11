package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.time.Instant
import java.util.UUID

class InMemoryWorkflowRunRepositoryTest {

    @Test
    fun `create and findById round-trip`() {
        val repo = InMemoryWorkflowRunRepository()
        val record = workflowRunRecord()

        repo.create(record)

        val found = repo.findById(record.id)
        assertNotNull(found)
        assertEquals(record.id, found.id)
        assertEquals(record.workflowName, found.workflowName)
        assertEquals(record.tenantId, found.tenantId)
        assertEquals(RunStatus.RUNNING, found.status)
    }

    @Test
    fun `findById returns null for unknown id`() {
        val repo = InMemoryWorkflowRunRepository()

        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `updateStatus changes the status`() {
        val repo = InMemoryWorkflowRunRepository()
        val record = workflowRunRecord()

        repo.create(record)
        repo.updateStatus(record.id, RunStatus.COMPLETED)

        val found = repo.findById(record.id)
        assertEquals(RunStatus.COMPLETED, found?.status)
    }

    @Test
    fun `updateStatus on unknown id does not fail`() {
        val repo = InMemoryWorkflowRunRepository()

        repo.updateStatus(UUID.randomUUID(), RunStatus.FAILED)
    }

    private fun workflowRunRecord(
        id: UUID = UUID.randomUUID(),
        workflowName: String = "test-workflow",
        tenantId: String = "tenant-1",
    ): WorkflowRunRecord =
        WorkflowRunRecord(
            id = id,
            workflowName = workflowName,
            tenantId = tenantId,
            status = RunStatus.RUNNING,
            input = "test-input",
            createdAt = Instant.EPOCH,
            completedAt = null,
        )
}
