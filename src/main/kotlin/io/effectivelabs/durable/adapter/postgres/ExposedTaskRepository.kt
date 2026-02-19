package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.port.TaskRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.ResultSet
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
            val sql = """
                UPDATE tasks
                SET pending_parent_count = pending_parent_count - 1,
                    status = CASE WHEN pending_parent_count - 1 = 0 THEN 'QUEUED' ELSE status END
                WHERE workflow_run_id = ?
                  AND status = 'PENDING'
                  AND ? = ANY(parent_names)
                RETURNING workflow_run_id, task_name, status, parent_names,
                          pending_parent_count, output, error, retry_count, max_retries,
                          created_at, started_at, completed_at
            """.trimIndent()

            exec(
                sql,
                args = listOf(UUIDColumnType() to workflowRunId, TextColumnType() to completedParentName),
                explicitStatementType = StatementType.EXEC,
            ) { rs ->
                val tasks = mutableListOf<TaskRecord>()
                while (rs.next()) {
                    if (rs.getString("status") == TaskState.QUEUED.name) {
                        tasks.add(rs.toTaskRecord())
                    }
                }
                tasks
            } ?: emptyList()
        }
    }

    private fun ResultSet.toTaskRecord(): TaskRecord {
        @Suppress("UNCHECKED_CAST")
        val parentNames = (getArray("parent_names").array as Array<*>).map { it.toString() }
        return TaskRecord(
            workflowRunId = UUID.fromString(getString("workflow_run_id")),
            taskName = getString("task_name"),
            status = TaskState.valueOf(getString("status")),
            parentNames = parentNames,
            pendingParentCount = getInt("pending_parent_count"),
            output = getString("output"),
            error = getString("error"),
            retryCount = getInt("retry_count"),
            maxRetries = getInt("max_retries"),
            createdAt = getTimestamp("created_at").toInstant(),
            startedAt = getTimestamp("started_at")?.toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant(),
        )
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
