package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState

class WorkflowCompletionChecker {

    fun check(tasks: List<TaskRecord>): RunStatus? {
        val allTerminal = tasks.all { it.status in TERMINAL_STATES }
        if (allTerminal) {
            val anyFailed = tasks.any { it.status == TaskState.FAILED }
            return if (anyFailed) RunStatus.FAILED else RunStatus.COMPLETED
        }

        val anyFailed = tasks.any { it.status == TaskState.FAILED }
        val anyActivelyProcessing = tasks.any { it.status in ACTIVE_STATES }
        if (anyFailed && !anyActivelyProcessing) {
            return RunStatus.FAILED
        }

        return null
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.CANCELLED,
            TaskState.SKIPPED,
        )

        private val ACTIVE_STATES = setOf(
            TaskState.RUNNING,
            TaskState.QUEUED,
            TaskState.SLEEPING,
        )
    }
}
