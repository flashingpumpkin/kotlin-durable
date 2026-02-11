package io.effectivelabs.durable.application

import io.effectivelabs.durable.adapter.inmemory.InMemoryEventRepository
import io.effectivelabs.durable.adapter.inmemory.InMemoryLeaderElection
import io.effectivelabs.durable.adapter.inmemory.InMemoryReadyQueueRepository
import io.effectivelabs.durable.adapter.inmemory.InMemoryTaskRepository
import io.effectivelabs.durable.adapter.inmemory.InMemoryTimerRepository
import io.effectivelabs.durable.adapter.inmemory.InMemoryWorkflowRegistry
import io.effectivelabs.durable.adapter.inmemory.InMemoryWorkflowRunRepository
import io.effectivelabs.durable.adapter.inmemory.SequentialIdGenerator
import io.effectivelabs.durable.adapter.time.FakeClock
import io.effectivelabs.durable.adapter.time.ManualScheduler
import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.WorkflowResult
import io.effectivelabs.durable.domain.port.DurableTaskEngine
import java.time.Duration
import java.util.UUID

class TestEngineBuilder {

    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val workflowRunRepository = InMemoryWorkflowRunRepository()
    val taskRepository = InMemoryTaskRepository()
    val readyQueueRepository = InMemoryReadyQueueRepository()
    val timerRepository = InMemoryTimerRepository()
    val eventRepository = InMemoryEventRepository()
    val workflowRegistry = InMemoryWorkflowRegistry()
    val leaderElection = InMemoryLeaderElection()
    val idGenerator = SequentialIdGenerator()

    fun build(): DurableTaskEngine {
        return DagTaskEngine(
            workflowRunRepository = workflowRunRepository,
            taskRepository = taskRepository,
            readyQueueRepository = readyQueueRepository,
            eventRepository = eventRepository,
            workflowRegistry = workflowRegistry,
            clock = clock,
            scheduler = scheduler,
            idGenerator = idGenerator,
        )
    }

    fun runUntilComplete(workflowRunId: UUID, maxIterations: Int = 1000): WorkflowResult {
        for (i in 0 until maxIterations) {
            val run = workflowRunRepository.findById(workflowRunId)
                ?: error("Workflow run $workflowRunId not found")

            if (run.status != RunStatus.RUNNING) {
                val tasks = taskRepository.findAllByWorkflowRunId(workflowRunId)
                val outputs = tasks.associate { it.taskName to it.output }
                return WorkflowResult(status = run.status, outputs = outputs)
            }

            clock.advance(Duration.ofMillis(TICK_ADVANCE_MS))
            scheduler.tick()
        }

        error("Workflow run $workflowRunId did not complete within $maxIterations iterations")
    }

    companion object {
        private const val TICK_ADVANCE_MS = 200L
    }
}
