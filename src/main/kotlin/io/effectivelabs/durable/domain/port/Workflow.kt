package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.WorkflowResult
import io.effectivelabs.durable.domain.model.WorkflowRunRef

interface Workflow<TInput> {
    val name: String
    fun run(input: TInput, tenantId: String): WorkflowResult
    fun runNoWait(input: TInput, tenantId: String): WorkflowRunRef
}
