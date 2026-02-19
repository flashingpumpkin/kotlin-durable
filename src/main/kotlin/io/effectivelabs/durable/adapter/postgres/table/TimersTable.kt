package io.effectivelabs.durable.adapter.postgres.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object TimersTable : Table("durable_timers") {
    val workflowRunId = uuid("workflow_run_id")
    val taskName = text("task_name")
    val tenantId = text("tenant_id")
    val wakeAt = timestamp("wake_at")
    val fired = bool("fired").default(false)

    override val primaryKey = PrimaryKey(workflowRunId, taskName)
}
