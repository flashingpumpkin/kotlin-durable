package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TaskEventsTable
import io.effectivelabs.durable.domain.model.TaskEvent
import io.effectivelabs.durable.domain.model.TaskEventType
import io.effectivelabs.durable.domain.port.EventRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedEventRepository(
    private val db: Database,
    private val table: TaskEventsTable = TaskEventsTable(),
) : EventRepository {

    override fun append(event: TaskEvent) {
        transaction(db) {
            table.insert {
                it[workflowRunId] = event.workflowRunId
                it[taskName] = event.taskName
                it[eventType] = event.eventType.name
                it[data] = event.data?.toString()
                it[timestamp] = event.timestamp
            }
        }
    }

    override fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent> {
        return transaction(db) {
            table.selectAll()
                .where { table.workflowRunId eq workflowRunId }
                .orderBy(table.id)
                .map { row ->
                    TaskEvent(
                        id = row[table.id],
                        workflowRunId = row[table.workflowRunId],
                        taskName = row[table.taskName],
                        eventType = TaskEventType.valueOf(row[table.eventType]),
                        data = row[table.data],
                        timestamp = row[table.timestamp],
                    )
                }
        }
    }
}
