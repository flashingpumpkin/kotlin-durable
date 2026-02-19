package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.ReadyQueueTable
import io.effectivelabs.durable.adapter.postgres.table.TaskEventsTable
import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.adapter.postgres.table.TimersTable
import io.effectivelabs.durable.adapter.postgres.table.WorkflowRunsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

abstract class PostgresTestBase {

    companion object {
        private val jdbcUrl = System.getenv("TEST_POSTGRES_URL")
            ?: "jdbc:postgresql://localhost:5432/durable"
        private val username = System.getenv("TEST_POSTGRES_USER") ?: "durable"
        private val password = System.getenv("TEST_POSTGRES_PASSWORD") ?: "durable"

        val db: Database by lazy {
            Database.connect(
                url = jdbcUrl,
                driver = "org.postgresql.Driver",
                user = username,
                password = password,
            ).also {
                transaction {
                    SchemaUtils.create(
                        WorkflowRunsTable,
                        TasksTable,
                        ReadyQueueTable,
                        TaskEventsTable,
                        TimersTable,
                    )
                }
            }
        }

        init {
            db // Trigger lazy initialization
        }
    }

    fun cleanTables() {
        transaction {
            TaskEventsTable.deleteAll()
            ReadyQueueTable.deleteAll()
            TimersTable.deleteAll()
            TasksTable.deleteAll()
            WorkflowRunsTable.deleteAll()
        }
    }
}
