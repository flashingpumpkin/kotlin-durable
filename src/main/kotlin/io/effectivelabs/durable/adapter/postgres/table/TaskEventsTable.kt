package io.effectivelabs.durable.adapter.postgres.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

class TaskEventsTable : Table("task_events") {
    val id = long("id").autoIncrement()
    val workflowRunId = uuid("workflow_run_id")
    val taskName = text("task_name")
    val eventType = text("event_type")
    val data = text("data").nullable()
    val timestamp = timestamp("timestamp")

    override val primaryKey = PrimaryKey(id)
}
