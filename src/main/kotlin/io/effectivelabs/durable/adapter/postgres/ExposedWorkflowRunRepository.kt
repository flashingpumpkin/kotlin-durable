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

class ExposedWorkflowRunRepository(private val db: Database) : WorkflowRunRepository {

    override fun create(record: WorkflowRunRecord) {
        transaction(db) {
            WorkflowRunsTable.insert {
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
            WorkflowRunsTable.selectAll()
                .where { WorkflowRunsTable.id eq id }
                .map { it.toWorkflowRunRecord() }
                .singleOrNull()
        }
    }

    override fun updateStatus(id: UUID, status: RunStatus) {
        transaction(db) {
            WorkflowRunsTable.update({ WorkflowRunsTable.id eq id }) {
                it[WorkflowRunsTable.status] = status.name
            }
        }
    }

    private fun ResultRow.toWorkflowRunRecord() = WorkflowRunRecord(
        id = this[WorkflowRunsTable.id],
        workflowName = this[WorkflowRunsTable.workflowName],
        tenantId = this[WorkflowRunsTable.tenantId],
        status = RunStatus.valueOf(this[WorkflowRunsTable.status]),
        input = this[WorkflowRunsTable.input],
        createdAt = this[WorkflowRunsTable.createdAt],
        completedAt = this[WorkflowRunsTable.completedAt],
    )
}
