package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import java.util.UUID

interface TaskRepository {
    fun createAll(records: List<TaskRecord>)
    fun findByName(workflowRunId: UUID, taskName: String): TaskRecord?
    fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord>
    fun updateStatus(workflowRunId: UUID, taskName: String, status: TaskState, output: Any? = null, error: String? = null)
    fun decrementPendingParents(workflowRunId: UUID, completedParentName: String): List<TaskRecord>
}
