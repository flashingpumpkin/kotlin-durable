package io.effectivelabs.durable.domain.model

data class WorkflowResult(
    val status: RunStatus,
    val outputs: Map<String, Any?>,
)
