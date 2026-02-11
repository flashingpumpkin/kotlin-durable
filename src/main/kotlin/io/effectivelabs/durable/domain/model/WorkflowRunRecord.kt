package io.effectivelabs.durable.domain.model

import java.time.Instant
import java.util.UUID

data class WorkflowRunRecord(
    val id: UUID,
    val workflowName: String,
    val tenantId: String,
    val status: RunStatus,
    val input: Any?,
    val createdAt: Instant,
    val completedAt: Instant?,
)
