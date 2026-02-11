package io.effectivelabs.durable.dsl

import io.effectivelabs.durable.domain.model.SkipCondition
import io.effectivelabs.durable.domain.model.StepRef

fun <T> skipWhen(ref: StepRef<T>, predicate: (T) -> Boolean): SkipCondition<T> =
    SkipCondition(ref, predicate)
