package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.ReadyQueueTable
import io.effectivelabs.durable.domain.model.QueueItem
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class ExposedReadyQueueRepository(
    private val db: Database,
    private val table: ReadyQueueTable = ReadyQueueTable(),
) : ReadyQueueRepository {

    override fun enqueue(item: QueueItem) {
        transaction(db) {
            table.insert {
                it[workflowRunId] = item.workflowRunId
                it[taskName] = item.taskName
                it[enqueuedAt] = item.enqueuedAt
            }
        }
    }

    override fun enqueueAll(items: List<QueueItem>) {
        transaction(db) {
            for (item in items) {
                table.insert {
                    it[workflowRunId] = item.workflowRunId
                    it[taskName] = item.taskName
                    it[enqueuedAt] = item.enqueuedAt
                }
            }
        }
    }

    override fun claim(batchSize: Int): List<QueueItem> {
        return transaction(db) {
            // Use raw SQL for SELECT FOR UPDATE SKIP LOCKED + DELETE
            val conn = this.connection.connection as java.sql.Connection
            val sql = """
                WITH claimed AS (
                    SELECT id, workflow_run_id, task_name, enqueued_at
                    FROM ${table.tableName}
                    ORDER BY id ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                DELETE FROM ${table.tableName}
                USING claimed
                WHERE ${table.tableName}.id = claimed.id
                RETURNING claimed.id, claimed.workflow_run_id, claimed.task_name, claimed.enqueued_at
            """.trimIndent()

            val stmt = conn.prepareStatement(sql)
            stmt.setInt(1, batchSize)
            val rs = stmt.executeQuery()

            val items = mutableListOf<QueueItem>()
            while (rs.next()) {
                items.add(
                    QueueItem(
                        id = rs.getLong("id"),
                        workflowRunId = java.util.UUID.fromString(rs.getString("workflow_run_id")),
                        taskName = rs.getString("task_name"),
                        enqueuedAt = rs.getTimestamp("enqueued_at").toInstant(),
                    )
                )
            }
            items
        }
    }
}
