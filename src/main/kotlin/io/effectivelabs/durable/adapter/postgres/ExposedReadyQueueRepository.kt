package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.ReadyQueueTable
import io.effectivelabs.durable.domain.model.QueueItem
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class ExposedReadyQueueRepository(
    private val db: Database,
    private val table: ReadyQueueTable = ReadyQueueTable(),
) : ReadyQueueRepository {

    override fun enqueue(item: QueueItem) {
        enqueueAll(listOf(item))
    }

    override fun enqueueAll(items: List<QueueItem>) {
        if (items.isEmpty()) return
        transaction(db) {
            val t = table
            t.batchInsert(items) { item ->
                this[t.workflowRunId] = item.workflowRunId
                this[t.taskName] = item.taskName
                this[t.enqueuedAt] = item.enqueuedAt
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

            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, batchSize)
                stmt.executeQuery().use { rs ->
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
    }
}
