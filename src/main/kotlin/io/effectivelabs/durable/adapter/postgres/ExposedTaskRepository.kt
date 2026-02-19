package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.port.TaskRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ExposedTaskRepository(
    private val db: Database,
    private val table: TasksTable = TasksTable(),
) : TaskRepository {

    override fun createAll(records: List<TaskRecord>) {
        transaction(db) {
            val t = table
            t.batchInsert(records) { record ->
                this[t.workflowRunId] = record.workflowRunId
                this[t.taskName] = record.taskName
                this[t.status] = record.status.name
                this[t.parentNames] = record.parentNames
                this[t.pendingParentCount] = record.pendingParentCount
                this[t.output] = record.output?.toString()
                this[t.error] = record.error
                this[t.retryCount] = record.retryCount
                this[t.maxRetries] = record.maxRetries
                this[t.createdAt] = record.createdAt
                this[t.startedAt] = record.startedAt
                this[t.completedAt] = record.completedAt
            }
        }
    }

    override fun findByName(workflowRunId: UUID, taskName: String): TaskRecord? {
        return transaction(db) {
            table.selectAll()
                .where { (table.workflowRunId eq workflowRunId) and (table.taskName eq taskName) }
                .map { it.toTaskRecord() }
                .singleOrNull()
        }
    }

    override fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord> {
        return transaction(db) {
            table.selectAll()
                .where { table.workflowRunId eq workflowRunId }
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
            table.update({
                (table.workflowRunId eq workflowRunId) and (table.taskName eq taskName)
            }) {
                it[table.status] = status.name
                if (output != null) it[table.output] = output.toString()
                if (error != null) it[table.error] = error
            }
        }
    }

    override fun decrementPendingParents(workflowRunId: UUID, completedParentName: String): List<TaskRecord> {
        return transaction(db) {
            // Find PENDING tasks that list completedParentName as a parent
            val candidates = table.selectAll()
                .where {
                    (table.workflowRunId eq workflowRunId) and
                        (table.status eq TaskState.PENDING.name)
                }
                .map { it.toTaskRecord() }
                .filter { completedParentName in it.parentNames }

            if (candidates.isEmpty()) return@transaction emptyList()

            val readyTasks = mutableListOf<TaskRecord>()

            for (task in candidates) {
                // Atomically decrement using a SQL expression to avoid read-modify-write race
                table.update({
                    (table.workflowRunId eq workflowRunId) and (table.taskName eq task.taskName)
                }) {
                    it[pendingParentCount] = table.pendingParentCount - 1
                }

                // If the task now has no remaining parents, mark it QUEUED
                table.update({
                    (table.workflowRunId eq workflowRunId) and
                        (table.taskName eq task.taskName) and
                        (table.pendingParentCount eq 0)
                }) {
                    it[status] = TaskState.QUEUED.name
                }

                val updated = table.selectAll()
                    .where {
                        (table.workflowRunId eq workflowRunId) and
                            (table.taskName eq task.taskName) and
                            (table.status eq TaskState.QUEUED.name)
                    }
                    .map { it.toTaskRecord() }
                    .singleOrNull()

                if (updated != null) readyTasks.add(updated)
            }

            readyTasks
        }
    }

    private fun ResultRow.toTaskRecord(): TaskRecord {
        return TaskRecord(
            workflowRunId = this[table.workflowRunId],
            taskName = this[table.taskName],
            status = TaskState.valueOf(this[table.status]),
            parentNames = this[table.parentNames],
            pendingParentCount = this[table.pendingParentCount],
            output = this[table.output],
            error = this[table.error],
            retryCount = this[table.retryCount],
            maxRetries = this[table.maxRetries],
            createdAt = this[table.createdAt],
            startedAt = this[table.startedAt],
            completedAt = this[table.completedAt],
        )
    }
}
