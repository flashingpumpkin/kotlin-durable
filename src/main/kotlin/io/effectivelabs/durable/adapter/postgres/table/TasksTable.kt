package io.effectivelabs.durable.adapter.postgres.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

class TasksTable(
    workflowRunsTable: WorkflowRunsTable = WorkflowRunsTable(),
) : Table("tasks") {
    val workflowRunId = uuid("workflow_run_id").references(workflowRunsTable.id)
    val taskName = text("task_name")
    val status = text("status").default("PENDING")
    val parentNames = array<String>("parent_names").default(emptyList())
    val pendingParentCount = integer("pending_parent_count").default(0)
    val output = text("output").nullable()
    val error = text("error").nullable()
    val retryCount = integer("retry_count").default(0)
    val maxRetries = integer("max_retries").default(0)
    val createdAt = timestamp("created_at")
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(workflowRunId, taskName)

    init {
        index(false, workflowRunId, status)
    }
}
