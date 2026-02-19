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

class ExposedTimerRepository(private val db: Database) : TimerRepository {

    override fun create(record: TimerRecord) {
        transaction(db) {
            TimersTable.insert {
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
            TimersTable.selectAll()
                .where { (TimersTable.wakeAt lessEq now) and (TimersTable.fired eq false) }
                .limit(limit)
                .map { row ->
                    TimerRecord(
                        workflowRunId = row[TimersTable.workflowRunId],
                        taskName = row[TimersTable.taskName],
                        tenantId = row[TimersTable.tenantId],
                        wakeAt = row[TimersTable.wakeAt],
                        fired = row[TimersTable.fired],
                    )
                }
        }
    }

    override fun markFired(record: TimerRecord) {
        transaction(db) {
            TimersTable.update({
                (TimersTable.workflowRunId eq record.workflowRunId) and
                    (TimersTable.taskName eq record.taskName)
            }) {
                it[fired] = true
            }
        }
    }
}
