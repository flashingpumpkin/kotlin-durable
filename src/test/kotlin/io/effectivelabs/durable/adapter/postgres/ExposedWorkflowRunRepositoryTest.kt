package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExposedWorkflowRunRepositoryTest : PostgresTestBase() {

    private val repo = ExposedWorkflowRunRepository()

    @BeforeTest
    fun setUp() {
        cleanTables()
    }

    @Test
    fun `create and findById returns the record`() {
        val id = UUID.randomUUID()
        val record = WorkflowRunRecord(
            id = id,
            workflowName = "test-workflow",
            tenantId = "tenant-1",
            status = RunStatus.RUNNING,
            input = "hello",
            createdAt = Instant.now(),
            completedAt = null,
        )

        repo.create(record)
        val found = repo.findById(id)

        assertNotNull(found)
        assertEquals("test-workflow", found.workflowName)
        assertEquals("tenant-1", found.tenantId)
        assertEquals(RunStatus.RUNNING, found.status)
    }

    @Test
    fun `findById returns null for nonexistent id`() {
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `updateStatus changes the status`() {
        val id = UUID.randomUUID()
        val record = WorkflowRunRecord(
            id = id,
            workflowName = "test-workflow",
            tenantId = "tenant-1",
            status = RunStatus.RUNNING,
            input = null,
            createdAt = Instant.now(),
            completedAt = null,
        )

        repo.create(record)
        repo.updateStatus(id, RunStatus.COMPLETED)

        val found = repo.findById(id)
        assertNotNull(found)
        assertEquals(RunStatus.COMPLETED, found.status)
    }
}
