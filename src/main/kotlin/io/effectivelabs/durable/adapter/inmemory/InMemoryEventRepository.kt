package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.TaskEvent
import io.effectivelabs.durable.domain.port.EventRepository
import java.util.UUID

class InMemoryEventRepository : EventRepository {

    private val events = mutableListOf<TaskEvent>()

    @Synchronized
    override fun append(event: TaskEvent) {
        events.add(event)
    }

    @Synchronized
    override fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent> =
        events.filter { it.workflowRunId == workflowRunId }.toList()
}
