package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.port.TaskRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ExposedTaskRepository(private val db: Database) : TaskRepository {

    override fun createAll(records: List<TaskRecord>) {
        transaction(db) {
            TasksTable.batchInsert(records) { record ->
                this[TasksTable.workflowRunId] = record.workflowRunId
                this[TasksTable.taskName] = record.taskName
                this[TasksTable.status] = record.status.name
                this[TasksTable.parentNames] = record.parentNames.joinToString(",")
                this[TasksTable.pendingParentCount] = record.pendingParentCount
                this[TasksTable.output] = record.output?.toString()
                this[TasksTable.error] = record.error
                this[TasksTable.retryCount] = record.retryCount
                this[TasksTable.maxRetries] = record.maxRetries
                this[TasksTable.createdAt] = record.createdAt
                this[TasksTable.startedAt] = record.startedAt
                this[TasksTable.completedAt] = record.completedAt
            }
        }
    }

    override fun findByName(workflowRunId: UUID, taskName: String): TaskRecord? {
        return transaction(db) {
            TasksTable.selectAll()
                .where { (TasksTable.workflowRunId eq workflowRunId) and (TasksTable.taskName eq taskName) }
                .map { it.toTaskRecord() }
                .singleOrNull()
        }
    }

    override fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord> {
        return transaction(db) {
            TasksTable.selectAll()
                .where { TasksTable.workflowRunId eq workflowRunId }
                .map { it.toTaskRecord() }
        }
    }

    override fun updateStatus(
        workflowRunId: UUID,
        taskName: String,
        status: TaskState,
        output: Any?,
        error: String?,
    ) {
        transaction(db) {
            TasksTable.update({
                (TasksTable.workflowRunId eq workflowRunId) and (TasksTable.taskName eq taskName)
            }) {
                it[TasksTable.status] = status.name
                if (output != null) it[TasksTable.output] = output.toString()
                if (error != null) it[TasksTable.error] = error
            }
        }
    }

    override fun decrementPendingParents(workflowRunId: UUID, completedParentName: String): List<TaskRecord> {
        return transaction(db) {
            // Find all PENDING children that have completedParentName as a parent
            val candidates = TasksTable.selectAll()
                .where {
                    (TasksTable.workflowRunId eq workflowRunId) and
                        (TasksTable.status eq TaskState.PENDING.name)
                }
                .map { it.toTaskRecord() }
                .filter { completedParentName in it.parentNames }

            val readyTasks = mutableListOf<TaskRecord>()

            for (task in candidates) {
                val newCount = task.pendingParentCount - 1
                val newStatus = if (newCount == 0) TaskState.QUEUED else TaskState.PENDING

                TasksTable.update({
                    (TasksTable.workflowRunId eq workflowRunId) and
                        (TasksTable.taskName eq task.taskName)
                }) {
                    it[pendingParentCount] = newCount
                    it[status] = newStatus.name
                }

                if (newCount == 0) {
                    readyTasks.add(task.copy(pendingParentCount = newCount, status = newStatus))
                }
            }

            readyTasks
        }
    }

    private fun ResultRow.toTaskRecord(): TaskRecord {
        val parentNamesStr = this[TasksTable.parentNames]
        return TaskRecord(
            workflowRunId = this[TasksTable.workflowRunId],
            taskName = this[TasksTable.taskName],
            status = TaskState.valueOf(this[TasksTable.status]),
            parentNames = if (parentNamesStr.isBlank()) emptyList() else parentNamesStr.split(","),
            pendingParentCount = this[TasksTable.pendingParentCount],
            output = this[TasksTable.output],
            error = this[TasksTable.error],
            retryCount = this[TasksTable.retryCount],
            maxRetries = this[TasksTable.maxRetries],
            createdAt = this[TasksTable.createdAt],
            startedAt = this[TasksTable.startedAt],
            completedAt = this[TasksTable.completedAt],
        )
    }
}
