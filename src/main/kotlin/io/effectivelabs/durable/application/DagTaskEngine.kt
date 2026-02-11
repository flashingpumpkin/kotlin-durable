package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.QueueItem
import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.model.WorkflowDefinition
import io.effectivelabs.durable.domain.model.WorkflowResult
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import io.effectivelabs.durable.domain.model.WorkflowRunRef
import io.effectivelabs.durable.domain.port.Clock
import io.effectivelabs.durable.domain.port.DurableTaskEngine
import io.effectivelabs.durable.domain.port.EventRepository
import io.effectivelabs.durable.domain.port.IdGenerator
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import io.effectivelabs.durable.domain.port.Scheduler
import io.effectivelabs.durable.domain.port.TaskRepository
import io.effectivelabs.durable.domain.port.Workflow
import io.effectivelabs.durable.domain.port.WorkflowBuilder
import io.effectivelabs.durable.domain.port.WorkflowRegistry
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import io.effectivelabs.durable.domain.service.WorkflowCompletionChecker
import java.time.Duration
import java.util.UUID

class DagTaskEngine(
    private val workflowRunRepository: WorkflowRunRepository,
    private val taskRepository: TaskRepository,
    private val readyQueueRepository: ReadyQueueRepository,
    private val eventRepository: EventRepository,
    private val workflowRegistry: WorkflowRegistry,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val idGenerator: IdGenerator,
    private val pollIntervalMs: Long = 200,
    private val batchSize: Int = 10,
) : DurableTaskEngine {

    private val completionChecker = WorkflowCompletionChecker()

    private val taskExecutor by lazy {
        TaskExecutor(
            taskRepository = taskRepository,
            workflowRunRepository = workflowRunRepository,
            readyQueueRepository = readyQueueRepository,
            eventRepository = eventRepository,
            workflowRegistry = workflowRegistry,
            completionChecker = completionChecker,
            clock = clock,
            idGenerator = idGenerator,
        )
    }

    private val taskPoller by lazy {
        TaskPoller(
            readyQueueRepository = readyQueueRepository,
            taskExecutor = taskExecutor,
            batchSize = batchSize,
        )
    }

    override fun <TInput> workflow(
        name: String,
        block: WorkflowBuilder<TInput>.() -> Unit,
    ): Workflow<TInput> {
        val builder = WorkflowBuilderImpl<TInput>()
        builder.block()
        val definition = builder.build().copy(name = name)
        workflowRegistry.register(name, definition)
        return WorkflowHandle(name = name, definition = definition, engine = this)
    }

    override fun start() {
        scheduler.scheduleRepeating("task-poller", pollIntervalMs, taskPoller)
    }

    override fun stop(timeout: Duration) {
        scheduler.shutdown()
    }

    fun trigger(definition: WorkflowDefinition, input: Any?, tenantId: String): WorkflowRunRef {
        val workflowRunId = UUID.randomUUID()
        val now = clock.now()

        val runRecord = WorkflowRunRecord(
            id = workflowRunId,
            workflowName = definition.name,
            tenantId = tenantId,
            status = RunStatus.RUNNING,
            input = input,
            createdAt = now,
            completedAt = null,
        )
        workflowRunRepository.create(runRecord)

        val taskRecords = definition.steps.map { step ->
            val isRoot = step.parents.isEmpty()
            TaskRecord(
                workflowRunId = workflowRunId,
                taskName = step.name,
                status = if (isRoot) TaskState.QUEUED else TaskState.PENDING,
                parentNames = step.parents.map { it.name },
                pendingParentCount = step.parents.size,
                output = null,
                error = null,
                retryCount = 0,
                maxRetries = step.retryPolicy.maxRetries,
                createdAt = now,
                startedAt = null,
                completedAt = null,
            )
        }
        taskRepository.createAll(taskRecords)

        val rootItems = taskRecords
            .filter { it.status == TaskState.QUEUED }
            .map { task ->
                QueueItem(
                    id = idGenerator.nextQueueId(),
                    workflowRunId = workflowRunId,
                    taskName = task.taskName,
                    enqueuedAt = now,
                )
            }
        readyQueueRepository.enqueueAll(rootItems)

        return WorkflowRunRef(workflowRunId)
    }

    fun awaitCompletion(workflowRunId: UUID): WorkflowResult {
        while (true) {
            val run = workflowRunRepository.findById(workflowRunId)
                ?: error("Workflow run $workflowRunId not found")

            if (run.status != RunStatus.RUNNING) {
                val tasks = taskRepository.findAllByWorkflowRunId(workflowRunId)
                val outputs = tasks.associate { it.taskName to it.output }
                return WorkflowResult(status = run.status, outputs = outputs)
            }

            Thread.sleep(50)
        }
    }
}
