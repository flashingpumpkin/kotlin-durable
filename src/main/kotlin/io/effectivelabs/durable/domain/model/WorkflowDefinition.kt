package io.effectivelabs.durable.domain.model

import io.effectivelabs.durable.domain.port.StepContext

data class WorkflowDefinition(
    val name: String,
    val steps: List<StepDefinition>,
    val onFailureHandler: ((Any?, StepContext) -> Unit)?,
)
