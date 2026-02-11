package io.effectivelabs.durable.domain.model

class SkipCondition<T>(
    val ref: StepRef<T>,
    val predicate: (T) -> Boolean,
)
