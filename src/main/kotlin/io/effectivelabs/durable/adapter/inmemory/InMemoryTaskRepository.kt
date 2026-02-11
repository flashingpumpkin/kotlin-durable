package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.port.TaskRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryTaskRepository : TaskRepository {

    private val store = ConcurrentHashMap<Pair<UUID, String>, TaskRecord>()

    override fun createAll(records: List<TaskRecord>) {
        for (record in records) {
            store[record.workflowRunId to record.taskName] = record
        }
    }

    override fun findByName(workflowRunId: UUID, taskName: String): TaskRecord? =
        store[workflowRunId to taskName]

    override fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord> =
        store.values.filter { it.workflowRunId == workflowRunId }

    override fun updateStatus(
        workflowRunId: UUID,
        taskName: String,
        status: TaskState,
        output: Any?,
        error: String?,
    ) {
        val key = workflowRunId to taskName
        store.computeIfPresent(key) { _, existing ->
            existing.copy(status = status, output = output ?: existing.output, error = error ?: existing.error)
        }
    }

    @Synchronized
    override fun decrementPendingParents(workflowRunId: UUID, completedParentName: String): List<TaskRecord> {
        val readyTasks = mutableListOf<TaskRecord>()

        for ((key, task) in store) {
            if (task.workflowRunId != workflowRunId) continue
            if (task.status != TaskState.PENDING) continue
            if (completedParentName !in task.parentNames) continue

            val newCount = task.pendingParentCount - 1
            val newStatus = if (newCount == 0) TaskState.QUEUED else task.status
            val updated = task.copy(pendingParentCount = newCount, status = newStatus)
            store[key] = updated

            if (newCount == 0) {
                readyTasks.add(updated)
            }
        }

        return readyTasks
    }
}
