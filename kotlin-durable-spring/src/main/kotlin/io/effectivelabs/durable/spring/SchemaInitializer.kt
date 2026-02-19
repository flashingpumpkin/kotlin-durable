package io.effectivelabs.durable.spring

import io.effectivelabs.durable.adapter.postgres.table.ReadyQueueTable
import io.effectivelabs.durable.adapter.postgres.table.TaskEventsTable
import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.adapter.postgres.table.TimersTable
import io.effectivelabs.durable.adapter.postgres.table.WorkflowRunsTable
import jakarta.annotation.PostConstruct
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class SchemaInitializer(
    private val db: Database,
    private val workflowRunsTable: WorkflowRunsTable,
    private val tasksTable: TasksTable,
    private val readyQueueTable: ReadyQueueTable,
    private val taskEventsTable: TaskEventsTable,
    private val timersTable: TimersTable,
) {

    @PostConstruct
    fun initializeSchema() {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                workflowRunsTable,
                tasksTable,
                readyQueueTable,
                taskEventsTable,
                timersTable,
            )
        }
    }
}
