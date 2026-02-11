package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.StepRef
import io.effectivelabs.durable.domain.port.StepContext
import io.effectivelabs.durable.domain.port.TaskRepository
import java.util.UUID

class StepContextImpl(
    override val workflowRunId: UUID,
    override val tenantId: String,
    override val attemptNumber: Int,
    private val taskRepository: TaskRepository,
) : StepContext {

    override fun <T> parentOutput(ref: StepRef<T>): T {
        val parentTask = taskRepository.findByName(workflowRunId, ref.name)
            ?: error("Parent task '${ref.name}' not found for workflow run $workflowRunId")

        @Suppress("UNCHECKED_CAST")
        return parentTask.output as T
    }

    override fun heartbeat() {
        // Not implemented in V4 scope
    }
}
