package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.RetryPolicy
import io.effectivelabs.durable.domain.model.SkipCondition
import io.effectivelabs.durable.domain.model.StepDefinition
import io.effectivelabs.durable.domain.model.StepRef
import io.effectivelabs.durable.domain.model.WorkflowDefinition
import io.effectivelabs.durable.domain.port.StepContext
import io.effectivelabs.durable.domain.port.WorkflowBuilder
import java.time.Duration

class WorkflowBuilderImpl<TInput> : WorkflowBuilder<TInput> {

    private val steps = mutableListOf<StepDefinition>()
    private val stepNames = mutableSetOf<String>()
    private var onFailureHandler: ((Any?, StepContext) -> Unit)? = null

    override fun <TOutput> step(
        name: String,
        parents: List<StepRef<*>>,
        retryPolicy: RetryPolicy,
        skipIf: List<SkipCondition<*>>,
        execute: (TInput, StepContext) -> TOutput,
    ): StepRef<TOutput> {
        require(stepNames.add(name)) { "Duplicate step name: '$name'" }

        @Suppress("UNCHECKED_CAST")
        val wrappedExecute: (Any?, StepContext) -> Any? = { input, ctx ->
            execute(input as TInput, ctx)
        }

        steps.add(
            StepDefinition(
                name = name,
                parents = parents,
                retryPolicy = retryPolicy,
                skipConditions = skipIf,
                isSleep = false,
                sleepDuration = null,
                execute = wrappedExecute,
            )
        )

        return StepRef(name)
    }

    override fun sleep(
        name: String,
        duration: Duration,
        parents: List<StepRef<*>>,
    ): StepRef<Unit> {
        require(stepNames.add(name)) { "Duplicate step name: '$name'" }

        steps.add(
            StepDefinition(
                name = name,
                parents = parents,
                retryPolicy = RetryPolicy.default(),
                skipConditions = emptyList(),
                isSleep = true,
                sleepDuration = duration,
                execute = null,
            )
        )

        return StepRef(name)
    }

    override fun onFailure(handler: (TInput, StepContext) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        onFailureHandler = handler as (Any?, StepContext) -> Unit
    }

    fun build(): WorkflowDefinition {
        for (step in steps) {
            for (parent in step.parents) {
                require(parent.name in stepNames) {
                    "Step '${step.name}' references non-existent parent '${parent.name}'"
                }
            }
        }
        return WorkflowDefinition(
            name = "",
            steps = steps.toList(),
            onFailureHandler = onFailureHandler,
        )
    }
}
