package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TaskEventsTable
import io.effectivelabs.durable.domain.model.TaskEvent
import io.effectivelabs.durable.domain.model.TaskEventType
import io.effectivelabs.durable.domain.port.EventRepository
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedEventRepository : EventRepository {

    override fun append(event: TaskEvent) {
        transaction {
            TaskEventsTable.insert {
                it[workflowRunId] = event.workflowRunId
                it[taskName] = event.taskName
                it[eventType] = event.eventType.name
                it[data] = event.data?.toString()
                it[timestamp] = event.timestamp
            }
        }
    }

    override fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent> {
        return transaction {
            TaskEventsTable.selectAll()
                .where { TaskEventsTable.workflowRunId eq workflowRunId }
                .orderBy(TaskEventsTable.id)
                .map { row ->
                    TaskEvent(
                        id = row[TaskEventsTable.id],
                        workflowRunId = row[TaskEventsTable.workflowRunId],
                        taskName = row[TaskEventsTable.taskName],
                        eventType = TaskEventType.valueOf(row[TaskEventsTable.eventType]),
                        data = row[TaskEventsTable.data],
                        timestamp = row[TaskEventsTable.timestamp],
                    )
                }
        }
    }
}
