package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState

class DagResolver {

    data class Resolution(val readyTaskNames: List<String>)

    fun resolveChildren(tasks: List<TaskRecord>, completedParentName: String): Resolution {
        val readyNames = mutableListOf<String>()

        for (task in tasks) {
            if (task.status != TaskState.PENDING) continue
            if (completedParentName !in task.parentNames) continue

            val newPendingCount = task.pendingParentCount - 1
            if (newPendingCount == 0) {
                readyNames.add(task.taskName)
            }
        }

        return Resolution(readyNames)
    }
}
