package io.effectivelabs.durable.domain.model

import java.time.Instant
import java.util.UUID

data class QueueItem(
    val id: Long,
    val workflowRunId: UUID,
    val taskName: String,
    val enqueuedAt: Instant,
)
