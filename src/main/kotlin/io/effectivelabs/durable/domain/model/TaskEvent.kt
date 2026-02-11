package io.effectivelabs.durable.domain.model

import java.time.Instant
import java.util.UUID

data class TaskEvent(
    val id: Long,
    val workflowRunId: UUID,
    val taskName: String,
    val eventType: TaskEventType,
    val data: Any?,
    val timestamp: Instant,
)
