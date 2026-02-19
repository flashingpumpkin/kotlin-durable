package io.effectivelabs.durable.adapter.postgres.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object WorkflowRunsTable : Table("workflow_runs") {
    val id = uuid("id")
    val workflowName = text("workflow_name")
    val tenantId = text("tenant_id")
    val status = text("status").default("RUNNING")
    val input = text("input").nullable()
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
