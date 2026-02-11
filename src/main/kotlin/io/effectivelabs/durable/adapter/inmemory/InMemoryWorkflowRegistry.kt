package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.WorkflowDefinition
import io.effectivelabs.durable.domain.port.WorkflowRegistry
import java.util.concurrent.ConcurrentHashMap

class InMemoryWorkflowRegistry : WorkflowRegistry {

    private val store = ConcurrentHashMap<String, WorkflowDefinition>()

    override fun register(name: String, definition: WorkflowDefinition) {
        store[name] = definition
    }

    override fun find(name: String): WorkflowDefinition? = store[name]
}
