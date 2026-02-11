package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.WorkflowDefinition
import io.effectivelabs.durable.domain.model.WorkflowResult
import io.effectivelabs.durable.domain.model.WorkflowRunRef
import io.effectivelabs.durable.domain.port.Workflow

class WorkflowHandle<TInput>(
    override val name: String,
    private val definition: WorkflowDefinition,
    private val engine: DagTaskEngine,
) : Workflow<TInput> {

    override fun run(input: TInput, tenantId: String): WorkflowResult {
        val ref = runNoWait(input, tenantId)
        return engine.awaitCompletion(ref.id)
    }

    override fun runNoWait(input: TInput, tenantId: String): WorkflowRunRef {
        return engine.trigger(definition, input, tenantId)
    }
}
