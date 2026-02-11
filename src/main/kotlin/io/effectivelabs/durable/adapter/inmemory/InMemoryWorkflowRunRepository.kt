package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryWorkflowRunRepository : WorkflowRunRepository {

    private val store = ConcurrentHashMap<UUID, WorkflowRunRecord>()

    override fun create(record: WorkflowRunRecord) {
        store[record.id] = record
    }

    override fun findById(id: UUID): WorkflowRunRecord? = store[id]

    override fun updateStatus(id: UUID, status: RunStatus) {
        store.computeIfPresent(id) { _, existing ->
            existing.copy(status = status)
        }
    }
}
