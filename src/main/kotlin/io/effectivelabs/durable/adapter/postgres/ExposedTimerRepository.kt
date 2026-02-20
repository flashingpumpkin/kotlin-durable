package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.TimersTable
import io.effectivelabs.durable.domain.model.TimerRecord
import io.effectivelabs.durable.domain.port.TimerRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class ExposedTimerRepository(
    private val db: Database,
    private val table: TimersTable = TimersTable(),
) : TimerRepository {

    override fun create(record: TimerRecord) {
        transaction(db) {
            table.insert {
                it[workflowRunId] = record.workflowRunId
                it[taskName] = record.taskName
                it[tenantId] = record.tenantId
                it[wakeAt] = record.wakeAt
                it[fired] = record.fired
            }
        }
    }

    override fun findExpired(now: Instant, limit: Int): List<TimerRecord> {
        return transaction(db) {
            table.selectAll()
                .where { (table.wakeAt lessEq now) and (table.fired eq false) }
                .limit(limit)
                .map { row ->
                    TimerRecord(
                        workflowRunId = row[table.workflowRunId],
                        taskName = row[table.taskName],
                        tenantId = row[table.tenantId],
                        wakeAt = row[table.wakeAt],
                        fired = row[table.fired],
                    )
                }
        }
    }

    override fun markFired(record: TimerRecord) {
        transaction(db) {
            table.update({
                (table.workflowRunId eq record.workflowRunId) and
                    (table.taskName eq record.taskName)
            }) {
                it[fired] = true
            }
        }
    }
}
