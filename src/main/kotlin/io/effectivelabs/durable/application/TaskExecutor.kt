package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.model.QueueItem
import io.effectivelabs.durable.domain.port.Clock
import io.effectivelabs.durable.domain.port.EventRepository
import io.effectivelabs.durable.domain.port.IdGenerator
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import io.effectivelabs.durable.domain.port.TaskRepository
import io.effectivelabs.durable.domain.port.WorkflowRegistry
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import io.effectivelabs.durable.domain.service.WorkflowCompletionChecker

class TaskExecutor(
    private val taskRepository: TaskRepository,
    private val workflowRunRepository: WorkflowRunRepository,
    private val readyQueueRepository: ReadyQueueRepository,
    private val eventRepository: EventRepository,
    private val workflowRegistry: WorkflowRegistry,
    private val completionChecker: WorkflowCompletionChecker,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) {

    fun execute(workflowRunId: java.util.UUID, taskName: String) {
        val task = taskRepository.findByName(workflowRunId, taskName)
            ?: error("Task '$taskName' not found for workflow run $workflowRunId")

        val workflowRun = workflowRunRepository.findById(workflowRunId)
            ?: error("Workflow run $workflowRunId not found")

        val definition = workflowRegistry.find(workflowRun.workflowName)
            ?: error("Workflow definition '${workflowRun.workflowName}' not found")

        val stepDef = definition.steps.find { it.name == taskName }
            ?: error("Step definition '$taskName' not found in workflow '${workflowRun.workflowName}'")

        taskRepository.updateStatus(workflowRunId, taskName, TaskState.RUNNING)

        val context = StepContextImpl(
            workflowRunId = workflowRunId,
            tenantId = workflowRun.tenantId,
            attemptNumber = task.retryCount + 1,
            taskRepository = taskRepository,
        )

        try {
            val executeFunction = stepDef.execute
                ?: error("Step '$taskName' has no execute function")

            val output = executeFunction(workflowRun.input, context)

            taskRepository.updateStatus(workflowRunId, taskName, TaskState.COMPLETED, output = output)

            resolveChildren(workflowRunId, taskName)
        } catch (e: Exception) {
            taskRepository.updateStatus(workflowRunId, taskName, TaskState.FAILED, error = e.message)
            checkWorkflowCompletion(workflowRunId)
        }
    }

    private fun resolveChildren(workflowRunId: java.util.UUID, completedTaskName: String) {
        val readyTasks = taskRepository.decrementPendingParents(workflowRunId, completedTaskName)

        if (readyTasks.isNotEmpty()) {
            val queueItems = readyTasks.map { task ->
                QueueItem(
                    id = idGenerator.nextQueueId(),
                    workflowRunId = workflowRunId,
                    taskName = task.taskName,
                    enqueuedAt = clock.now(),
                )
            }
            readyQueueRepository.enqueueAll(queueItems)
        }

        checkWorkflowCompletion(workflowRunId)
    }

    private fun checkWorkflowCompletion(workflowRunId: java.util.UUID) {
        val allTasks = taskRepository.findAllByWorkflowRunId(workflowRunId)
        val terminalStatus = completionChecker.check(allTasks) ?: return

        workflowRunRepository.updateStatus(workflowRunId, terminalStatus)
    }
}
