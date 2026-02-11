package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import java.util.UUID

interface WorkflowRunRepository {
    fun create(record: WorkflowRunRecord)
    fun findById(id: UUID): WorkflowRunRecord?
    fun updateStatus(id: UUID, status: RunStatus)
}
