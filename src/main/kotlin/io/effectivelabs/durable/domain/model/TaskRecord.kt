package io.effectivelabs.durable.domain.model

import java.time.Instant
import java.util.UUID

data class TaskRecord(
    val workflowRunId: UUID,
    val taskName: String,
    val status: TaskState,
    val parentNames: List<String>,
    val pendingParentCount: Int,
    val output: Any?,
    val error: String?,
    val retryCount: Int,
    val maxRetries: Int,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)
