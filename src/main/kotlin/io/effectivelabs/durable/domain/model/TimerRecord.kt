package io.effectivelabs.durable.domain.model

import java.time.Instant
import java.util.UUID

data class TimerRecord(
    val workflowRunId: UUID,
    val taskName: String,
    val tenantId: String,
    val wakeAt: Instant,
    val fired: Boolean,
)
