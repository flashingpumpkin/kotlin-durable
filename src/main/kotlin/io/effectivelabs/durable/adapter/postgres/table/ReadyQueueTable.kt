package io.effectivelabs.durable.adapter.postgres.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

class ReadyQueueTable : Table("ready_queue") {
    val id = long("id").autoIncrement()
    val workflowRunId = uuid("workflow_run_id")
    val taskName = text("task_name")
    val enqueuedAt = timestamp("enqueued_at")

    override val primaryKey = PrimaryKey(id)
}
