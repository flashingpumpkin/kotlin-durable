package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.WorkflowDefinition

interface WorkflowRegistry {
    fun register(name: String, definition: WorkflowDefinition)
    fun find(name: String): WorkflowDefinition?
}
