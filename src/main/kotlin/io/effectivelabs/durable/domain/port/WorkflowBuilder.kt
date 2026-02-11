package io.effectivelabs.durable.domain.port

import io.effectivelabs.durable.domain.model.RetryPolicy
import io.effectivelabs.durable.domain.model.SkipCondition
import io.effectivelabs.durable.domain.model.StepRef
import java.time.Duration

interface WorkflowBuilder<TInput> {
    fun <TOutput> step(
        name: String,
        parents: List<StepRef<*>> = emptyList(),
        retryPolicy: RetryPolicy = RetryPolicy.default(),
        skipIf: List<SkipCondition<*>> = emptyList(),
        execute: (TInput, StepContext) -> TOutput,
    ): StepRef<TOutput>

    fun sleep(
        name: String,
        duration: Duration,
        parents: List<StepRef<*>> = emptyList(),
    ): StepRef<Unit>

    fun onFailure(handler: (TInput, StepContext) -> Unit)
}
