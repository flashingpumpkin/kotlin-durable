package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.StepRef
import java.util.UUID

interface StepContext {
    val workflowRunId: UUID
    val tenantId: String
    val attemptNumber: Int
    fun <T> parentOutput(ref: StepRef<T>): T
    fun heartbeat()
}
