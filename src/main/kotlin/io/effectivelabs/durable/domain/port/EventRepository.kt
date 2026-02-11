package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.TaskEvent
import java.util.UUID

interface EventRepository {
    fun append(event: TaskEvent)
    fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent>
}
