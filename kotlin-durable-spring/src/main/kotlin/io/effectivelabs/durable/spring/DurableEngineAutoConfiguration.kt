package io.effectivelabs.durable.spring

import io.effectivelabs.durable.adapter.inmemory.InMemoryWorkflowRegistry
import io.effectivelabs.durable.application.DagTaskEngine
import io.effectivelabs.durable.domain.port.Clock
import io.effectivelabs.durable.domain.port.DurableTaskEngine
import io.effectivelabs.durable.domain.port.EventRepository
import io.effectivelabs.durable.domain.port.IdGenerator
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import io.effectivelabs.durable.domain.port.Scheduler
import io.effectivelabs.durable.domain.port.TaskRepository
import io.effectivelabs.durable.domain.port.WorkflowRegistry
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [DurableExposedAutoConfiguration::class])
open class DurableEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowRegistry::class)
    open fun workflowRegistry(): WorkflowRegistry {
        return InMemoryWorkflowRegistry()
    }

    @Bean
    @ConditionalOnMissingBean(DurableTaskEngine::class)
    open fun durableTaskEngine(
        workflowRunRepository: WorkflowRunRepository,
        taskRepository: TaskRepository,
        readyQueueRepository: ReadyQueueRepository,
        eventRepository: EventRepository,
        workflowRegistry: WorkflowRegistry,
        clock: Clock,
        scheduler: Scheduler,
        idGenerator: IdGenerator,
        @Value("\${durable.poll-interval-ms:200}") pollIntervalMs: Long,
        @Value("\${durable.batch-size:10}") batchSize: Int,
    ): DurableTaskEngine {
        return DagTaskEngine(
            workflowRunRepository = workflowRunRepository,
            taskRepository = taskRepository,
            readyQueueRepository = readyQueueRepository,
            eventRepository = eventRepository,
            workflowRegistry = workflowRegistry,
            clock = clock,
            scheduler = scheduler,
            idGenerator = idGenerator,
            pollIntervalMs = pollIntervalMs,
            batchSize = batchSize,
        )
    }

    @Bean
    open fun durableTaskEngineLifecycle(
        durableTaskEngine: DurableTaskEngine,
    ): DurableTaskEngineLifecycle {
        return DurableTaskEngineLifecycle(durableTaskEngine)
    }
}
