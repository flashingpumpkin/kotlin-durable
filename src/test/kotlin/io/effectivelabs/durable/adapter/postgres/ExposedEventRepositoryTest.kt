package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.model.TaskEvent
import io.effectivelabs.durable.domain.model.TaskEventType
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExposedEventRepositoryTest : PostgresTestBase() {

    private val repo = ExposedEventRepository(db)

    @BeforeTest
    fun setUp() {
        cleanTables()
    }

    @Test
    fun `append and findByWorkflowRunId returns events in order`() {
        val runId = UUID.randomUUID()

        repo.append(
            TaskEvent(
                id = 0,
                workflowRunId = runId,
                taskName = "a",
                eventType = TaskEventType.QUEUED,
                data = null,
                timestamp = Instant.now(),
            )
        )
        repo.append(
            TaskEvent(
                id = 0,
                workflowRunId = runId,
                taskName = "a",
                eventType = TaskEventType.STARTED,
                data = null,
                timestamp = Instant.now(),
            )
        )
        repo.append(
            TaskEvent(
                id = 0,
                workflowRunId = runId,
                taskName = "a",
                eventType = TaskEventType.COMPLETED,
                data = "result",
                timestamp = Instant.now(),
            )
        )

        val events = repo.findByWorkflowRunId(runId)
        assertEquals(3, events.size)
        assertEquals(TaskEventType.QUEUED, events[0].eventType)
        assertEquals(TaskEventType.STARTED, events[1].eventType)
        assertEquals(TaskEventType.COMPLETED, events[2].eventType)
        assertEquals("result", events[2].data)
    }

    @Test
    fun `findByWorkflowRunId returns empty for nonexistent run`() {
        val events = repo.findByWorkflowRunId(UUID.randomUUID())
        assertEquals(0, events.size)
    }
}
