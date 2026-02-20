package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.postgres.table.WorkflowRunsTable
import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ExposedWorkflowRunRepository(
    private val db: Database,
    private val table: WorkflowRunsTable = WorkflowRunsTable(),
) : WorkflowRunRepository {

    override fun create(record: WorkflowRunRecord) {
        transaction(db) {
            table.insert {
                it[id] = record.id
                it[workflowName] = record.workflowName
                it[tenantId] = record.tenantId
                it[status] = record.status.name
                it[input] = record.input?.toString()
                it[createdAt] = record.createdAt
                it[completedAt] = record.completedAt
            }
        }
    }

    override fun findById(id: UUID): WorkflowRunRecord? {
        return transaction(db) {
            table.selectAll()
                .where { table.id eq id }
                .map { it.toWorkflowRunRecord() }
                .singleOrNull()
        }
    }

    override fun updateStatus(id: UUID, status: RunStatus) {
        transaction(db) {
            table.update({ table.id eq id }) {
                it[table.status] = status.name
            }
        }
    }

    private fun ResultRow.toWorkflowRunRecord() = WorkflowRunRecord(
        id = this[table.id],
        workflowName = this[table.workflowName],
        tenantId = this[table.tenantId],
        status = RunStatus.valueOf(this[table.status]),
        input = this[table.input],
        createdAt = this[table.createdAt],
        completedAt = this[table.completedAt],
    )
}
