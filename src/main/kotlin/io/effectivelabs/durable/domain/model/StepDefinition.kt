package io.effectivelabs.durable.domain.model

import io.effectivelabs.durable.domain.port.StepContext
import java.time.Duration

data class StepDefinition(
    val name: String,
    val parents: List<StepRef<*>>,
    val retryPolicy: RetryPolicy,
    val skipConditions: List<SkipCondition<*>>,
    val isSleep: Boolean,
    val sleepDuration: Duration?,
    val execute: ((Any?, StepContext) -> Any?)?,
)
