package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.StepDefinition
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState

class SkipEvaluator {

    fun shouldSkip(step: StepDefinition, parentTasks: Map<String, TaskRecord>): Boolean {
        val anyParentSkipped = parentTasks.values.any { it.status == TaskState.SKIPPED }
        if (anyParentSkipped) return true

        for (condition in step.skipConditions) {
            val parentTask = parentTasks[condition.ref.name] ?: continue
            val output = parentTask.output

            @Suppress("UNCHECKED_CAST")
            val predicate = condition.predicate as (Any?) -> Boolean
            if (predicate(output)) return true
        }

        return false
    }
}
